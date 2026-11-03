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
 * 本地 SSE emitter 注册表 + 投递。
 *
 * 多副本下仅持有**本副本**的连接：事件由 Redis 事件总线发布 → 各副本 relay 调
 * {@link #sendBySession}/{@link #sendByUser} 投本地 emitter；缓存/回放由 {@link SseEventCache}
 * 承担（origin 写、注册时 flush）。
 */
@Slf4j
@Component
public class SseManager {

    private final ConcurrentHashMap<Long, List<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();
    /** username → connectionId → 连接（同一用户多台浏览器时各占一条）。 */
    private final ConcurrentHashMap<String, Map<String, UserConnection>> userConnections = new ConcurrentHashMap<>();
    private final SseEventCache sseEventCache;
    private final UserConnectionRegistry userConnectionRegistry;
    private final ScheduledExecutorService heartbeats;
    /** 缓存回放专用线程：回归放不阻塞连接建立（避免 register 因 Redis 慢卡住 → 前端连接超时）。 */
    private final ExecutorService cacheFlushes;

    /** 一条用户级 SSE 连接（含设备信息，供定向投递与登记表使用）。 */
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
                        // 顺带续期并重写登记表：Redis 重启/被淘汰后靠这里自愈（≤1 个心跳周期）
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

    /** 缓存回放 fire-and-forget：失败不影响连接建立，只留日志（Redis 抖动降级为空回放）。 */
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

        // 跨副本重连：回放 Redis 缓存中该 session 的近端事件（异步，不阻塞连接建立）
        asyncFlush(() -> sseEventCache.flushSessionEvents(sessionId, emitter));
        return emitter;
    }

    /** 本副本 session 级投递（relay 调用；origin 已在发布前写缓存）。 */
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

    // ---------- 用户级连接（task 指令投递，不依赖 session） ----------

    /**
     * 注册用户级 SSE 连接。同一用户可有多条（多台浏览器），
     * 由 {@link UserConnectionRegistry} 统一登记，投递时按设备定向。
     */
    public SseEmitter registerUser(String username, String deviceId, String deviceName, String source) {
        // 同一设备重连：先关掉本副本上它的旧连接，避免旧连接的“幽灵心跳”把登记表又写回来
        Map<String, UserConnection> existing = userConnections.get(username);
        if (existing != null && deviceId != null && !deviceId.isBlank()) {
            for (UserConnection old : new ArrayList<>(existing.values())) {
                if (deviceId.equals(old.deviceId())) {
                    try {
                        old.emitter().complete();
                    } catch (Exception ignored) {
                        // 已经断了就忽略
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

        // 用户级缓存回放（异步，不阻塞连接建立）
        asyncFlush(() -> sseEventCache.flushUserEvents(username, emitter));
        return emitter;
    }

    /**
     * 投递给指定的用户级连接。
     *
     * @return 本副本是否持有该连接并投递成功
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
