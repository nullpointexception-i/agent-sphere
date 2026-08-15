package com.buukle.agent.runtime.orchestration.sse;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 本地 SSE emitter 注册表 + 投递。
 *
 * 多副本下仅持有**本副本**的连接：事件由 Redis 事件总线发布 → 各副本 relay 调
 * {@link #sendBySession}/{@link #sendByUser} 投本地 emitter；缓存/回放由 {@link SseEventCache}
 * 承担（origin 写、注册时 flush）。
 */
@Component
public class SseManager {

    private final ConcurrentHashMap<Long, List<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    private final SseEventCache sseEventCache;
    private final ScheduledExecutorService heartbeats;

    public SseManager(AgentRuntimeProperties properties, SseEventCache sseEventCache) {
        this.sseEventCache = sseEventCache;
        long heartbeatInterval = properties.getSse().getHeartbeatInterval().getSeconds();
        this.heartbeats = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeats.scheduleAtFixedRate(this::heartbeatTask, heartbeatInterval, heartbeatInterval, TimeUnit.SECONDS);
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
            for (Map.Entry<String, List<SseEmitter>> entry : userEmitters.entrySet()) {
                String username = entry.getKey();
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
                    if (emitters.isEmpty()) userEmitters.remove(username, emitters);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdown();
        try {
            if (!heartbeats.awaitTermination(5, TimeUnit.SECONDS)) heartbeats.shutdownNow();
        } catch (InterruptedException e) {
            heartbeats.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public SseEmitter register(Long sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> removeEmitter(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 跨副本重连：回放 Redis 缓存中该 session 的近端事件
        sseEventCache.flushSessionEvents(sessionId, emitter);
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

    /** 注册用户级 SSE 连接（key=username）。 */
    public SseEmitter registerUser(String username) {
        SseEmitter emitter = new SseEmitter(0L);
        userEmitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> removeUserEmitter(username, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        sseEventCache.flushUserEvents(username, emitter);
        return emitter;
    }

    /** 本副本用户级投递。 */
    public void sendByUser(String username, Object event) {
        List<SseEmitter> emitters = userEmitters.get(username);
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
            List<SseEmitter> remaining = userEmitters.get(username);
            if (remaining != null) remaining.removeAll(failed);
        }
    }

    private void removeUserEmitter(String username, SseEmitter emitter) {
        List<SseEmitter> emitters = userEmitters.get(username);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                userEmitters.remove(username);
            }
        }
    }
}
