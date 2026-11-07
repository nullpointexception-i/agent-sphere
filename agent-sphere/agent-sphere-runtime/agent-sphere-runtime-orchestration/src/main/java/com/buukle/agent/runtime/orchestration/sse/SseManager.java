package com.buukle.agent.runtime.orchestration.sse;

import com.buukle.agent.common.chrome.UserConnectionRegistry;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Local SSE emitter registry + delivery.
 *
 * Under multiple replicas it holds only **this replica's** connections: events are published on the Redis event bus → each replica's relay
 * calls {@link #sendBySession}/{@link #sendByUser} to deliver to local emitters; caching/replay is handled by {@link SseEventCache}
 * (origin writes, flush on registration).
 */
@Slf4j
@Component
public class SseManager {

    private final ConcurrentHashMap<Long, List<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();
    /** username → connectionId → connection (one entry per browser when a user has several). */
    private final ConcurrentHashMap<String, Map<String, UserConnection>> userConnections = new ConcurrentHashMap<>();
    private final SseEventCache sseEventCache;
    private final UserConnectionRegistry userConnectionRegistry;
    private final ScheduledExecutorService heartbeats;
    /** Dedicated cache-replay thread: replay does not block connection setup (otherwise a slow Redis would stall register → frontend connection timeout). */
    private final ExecutorService cacheFlushes;

    /** One user-level SSE connection (with device info, used for targeted delivery and the registry). */
    public record UserConnection(String connectionId, String username, String deviceId, String deviceName,
                                 String source, long connectedAt, SseEmitter emitter) {
    }

    public SseManager(AgentRuntimeProperties properties, SseEventCache sseEventCache,
                      UserConnectionRegistry userConnectionRegistry) {
        this.sseEventCache = sseEventCache;
        this.userConnectionRegistry = userConnectionRegistry;
        long heartbeatInterval = properties.getSse().getHeartbeatInterval().getSeconds();
        this.heartbeats = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeats.scheduleAtFixedRate(this::heartbeatTask, heartbeatInterval, heartbeatInterval, TimeUnit.SECONDS);
        this.cacheFlushes = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-cache-flush");
            t.setDaemon(true);
            return t;
        });
    }

    private void heartbeatTask() {
        try {
            for (Map.Entry<Long, List<SseEmitter>> entry : sessionEmitters.entrySet()) {
                Long sid = entry.getKey();
                List<SseEmitter> emitters = entry.getValue();
                List<SseEmitter> dead = new ArrayList<>();
                for (SseEmitter emitter : emitters) {
                    try {
                        emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                    } catch (Exception e) {
                        dead.add(emitter);
                    }
                }
                if (!dead.isEmpty()) {
                    emitters.removeAll(dead);
                    if (emitters.isEmpty()) sessionEmitters.remove(sid, emitters);
                }
            }
            for (Map.Entry<String, Map<String, UserConnection>> entry : userConnections.entrySet()) {
                String username = entry.getKey();
                for (UserConnection connection : new ArrayList<>(entry.getValue().values())) {
                    try {
                        connection.emitter().send(SseEmitter.event().name("heartbeat").data("ping"));
                        // Also renew and rewrite the registry entry: after a Redis restart/eviction this self-heals within one heartbeat cycle
                        userConnectionRegistry.touch(username, new UserConnectionRegistry.ConnectionView(
                                connection.connectionId(), connection.deviceId(), connection.deviceName(),
                                connection.source(), connection.connectedAt(), System.currentTimeMillis()));
                    } catch (Exception e) {
                        removeUserConnection(username, connection.connectionId());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdown();
        cacheFlushes.shutdown();
        try {
            if (!heartbeats.awaitTermination(5, TimeUnit.SECONDS)) heartbeats.shutdownNow();
        } catch (InterruptedException e) {
            heartbeats.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Cache replay is fire-and-forget: failure does not affect connection setup, only a log line (Redis flakiness degrades to empty replay). */
    private void asyncFlush(Runnable task) {
        try {
            cacheFlushes.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("SSE cache-flush failed: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("SSE cache-flush rejected (shutting down): {}", e.getMessage());
        }
    }

    public SseEmitter register(Long sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> removeEmitter(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Cross-replica reconnect: replay this session's recent events from the Redis cache (async, does not block connection setup)
        asyncFlush(() -> sseEventCache.flushSessionEvents(sessionId, emitter));
        return emitter;
    }

    /** Session-level delivery on this replica (called by relay; origin already wrote the cache before publishing). */
    public void sendBySession(Long sessionId, Object event) {
        List<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (Exception e) {
                failed.add(emitter);
            }
        }
        if (!failed.isEmpty()) {
            List<SseEmitter> remaining = sessionEmitters.get(sessionId);
            if (remaining != null) remaining.removeAll(failed);
        }
    }

    private void removeEmitter(Long sessionId, SseEmitter emitter) {
        List<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                sessionEmitters.remove(sessionId);
            }
        }
    }

    // ---------- User-level connections (task command delivery, independent of any session) ----------

    /**
     * Register a user-level SSE connection. A user may have several (multiple browsers);
     * they are registered uniformly by {@link UserConnectionRegistry} and delivery is device-targeted.
     */
    public SseEmitter registerUser(String username, String deviceId, String deviceName, String source) {
        // Same device reconnecting: close its old connection on this replica first, so the old connection's "ghost heartbeat" cannot write the registry entry back
        Map<String, UserConnection> existing = userConnections.get(username);
        if (existing != null && deviceId != null && !deviceId.isBlank()) {
            for (UserConnection old : new ArrayList<>(existing.values())) {
                if (deviceId.equals(old.deviceId())) {
                    try {
                        old.emitter().complete();
                    } catch (Exception ignored) {
                        // Already disconnected; ignore
                    }
                    removeUserConnection(username, old.connectionId());
                }
            }
        }

        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        UserConnection connection = new UserConnection(connectionId, username, deviceId, deviceName, source,
                System.currentTimeMillis(), emitter);
        userConnections.computeIfAbsent(username, k -> new ConcurrentHashMap<>()).put(connectionId, connection);
        userConnectionRegistry.register(username, connectionId, deviceId, deviceName, source,
                connection.connectedAt());

        Runnable cleanup = () -> removeUserConnection(username, connectionId);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // User-level cache replay (async, does not block connection setup)
        asyncFlush(() -> sseEventCache.flushUserEvents(username, emitter));
        return emitter;
    }

    /**
     * Deliver to the specified user-level connection.
     *
     * @return whether this replica holds the connection and delivered successfully
     */
    public boolean sendToConnection(String username, String connectionId, Object event) {
        if (username == null || connectionId == null) {
            return false;
        }
        Map<String, UserConnection> connections = userConnections.get(username);
        if (connections == null) {
            return false;
        }
        UserConnection connection = connections.get(connectionId);
        if (connection == null) {
            return false;
        }
        try {
            connection.emitter().send(SseEmitter.event().data(event));
            return true;
        } catch (Exception e) {
            removeUserConnection(username, connectionId);
            return false;
        }
    }

    private void removeUserConnection(String username, String connectionId) {
        Map<String, UserConnection> connections = userConnections.get(username);
        if (connections != null) {
            connections.remove(connectionId);
            if (connections.isEmpty()) {
                userConnections.remove(username, connections);
            }
        }
        userConnectionRegistry.unregister(username, connectionId);
    }
}
