package com.buukle.agent.runtime.orchestration.sse;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class SseManager {
    private static final int MAX_CACHE_PER_SESSION = 300;

    private final ConcurrentHashMap<Long, List<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<CachedEvent>> eventCache = new ConcurrentHashMap<>();
    private final Duration cacheTtl;
    private final ScheduledExecutorService heartbeats;

    public SseManager(AgentRuntimeProperties properties) {
        this.cacheTtl = properties.getSse().getCacheTtl();
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

        flushCache(sessionId, emitter);
        return emitter;
    }

    public void sendBySession(Long sessionId, Object event) {
        // 新 run 开始时清空该 session 的缓存，避免重连后重放旧 run 的事件
        if (event instanceof RuntimeEventVO re
                && re.getEventType() instanceof RunStatus s
                && s == RunStatus.PENDING
                && re.getData() != null && re.getData().getSessionId() != null) {
            ConcurrentLinkedQueue<CachedEvent> q = eventCache.get(re.getData().getSessionId());
            if (q != null) q.clear();
        }

        List<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            cacheEvent(sessionId, event);
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
            cacheEvent(sessionId, event);
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

    private void cacheEvent(Long sessionId, Object event) {
        ConcurrentLinkedQueue<CachedEvent> queue = eventCache.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>());
        queue.add(new CachedEvent(event, Instant.now()));
        while (queue.size() > MAX_CACHE_PER_SESSION) queue.poll();
        evictExpired(sessionId, queue);
    }

    private void flushCache(Long sessionId, SseEmitter emitter) {
        ConcurrentLinkedQueue<CachedEvent> queue = eventCache.get(sessionId);
        if (queue == null || queue.isEmpty()) return;
        List<CachedEvent> remaining = new ArrayList<>();
        while (!queue.isEmpty()) {
            CachedEvent cached = queue.poll();
            if (cached != null && Duration.between(cached.timestamp, Instant.now()).compareTo(cacheTtl) <= 0) {
                try {
                    emitter.send(SseEmitter.event().data(cached.event));
                } catch (Exception e) {
                    remaining.add(cached);
                }
            }
        }
        if (!remaining.isEmpty()) {
            queue.addAll(remaining);
        }
        if (queue.isEmpty()) {
            eventCache.remove(sessionId);
        }
    }

    private void evictExpired(Long sessionId, ConcurrentLinkedQueue<CachedEvent> queue) {
        Iterator<CachedEvent> it = queue.iterator();
        while (it.hasNext()) {
            if (Duration.between(it.next().timestamp, Instant.now()).compareTo(cacheTtl) > 0) {
                it.remove();
            }
        }
        if (queue.isEmpty()) {
            eventCache.remove(sessionId);
        }
    }

    private record CachedEvent(Object event, Instant timestamp) {
    }
}
