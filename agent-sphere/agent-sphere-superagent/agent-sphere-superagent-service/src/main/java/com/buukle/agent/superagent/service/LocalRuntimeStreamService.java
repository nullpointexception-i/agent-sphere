package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.service.event.LocalRuntimeStreamEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime-scoped SSE notifications. Each replica only holds its own connections;
 * task wakes are published to a Redis topic so the pod holding the desktop receives it.
 */
@Slf4j
@Service
public class LocalRuntimeStreamService {

    private static final String TOPIC_PREFIX = "local:runtime:";
    private static final long SSE_TIMEOUT_MS = 0L;

    private final RedissonClient eventBusClient;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Set<Long> subscribed = ConcurrentHashMap.newKeySet();

    public LocalRuntimeStreamService(
            @Qualifier("eventBusRedissonClient") RedissonClient eventBusClient) {
        this.eventBusClient = eventBusClient;
    }

    public SseEmitter register(Long runtimeId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(runtimeId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        ensureSubscribed(runtimeId);

        emitter.onCompletion(() -> remove(runtimeId, emitter));
        emitter.onTimeout(() -> remove(runtimeId, emitter));
        emitter.onError(error -> remove(runtimeId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (Exception e) {
            remove(runtimeId, emitter);
        }
        return emitter;
    }

    public void publish(Long runtimeId, LocalRuntimeStreamEvent event) {
        if (runtimeId == null || event == null) return;
        try {
            topic(runtimeId).publish(event);
        } catch (Exception e) {
            log.warn("Local runtime SSE publish failed, fallback to local: runtime={}, error={}",
                    runtimeId, e.getMessage());
            deliver(runtimeId, event);
        }
    }

    public void publishTaskReady(Long runtimeId, Long taskId) {
        publish(runtimeId, new LocalRuntimeStreamEvent("task_ready", taskId, null));
    }

    @PreDestroy
    public void shutdown() {
        emitters.forEach((runtimeId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        });
        emitters.clear();
    }

    private void ensureSubscribed(Long runtimeId) {
        if (!subscribed.add(runtimeId)) return;
        topic(runtimeId).addListener(LocalRuntimeStreamEvent.class,
                (channel, event) -> deliver(runtimeId, event));
    }

    private RTopic topic(Long runtimeId) {
        return eventBusClient.getTopic(TOPIC_PREFIX + runtimeId);
    }

    private void deliver(Long runtimeId, LocalRuntimeStreamEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(runtimeId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event.getType()).data(event));
            } catch (Exception e) {
                remove(runtimeId, emitter);
            }
        }
    }

    private void remove(Long runtimeId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(runtimeId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(runtimeId);
    }
}
