package com.buukle.agent.im.service.impl;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.service.ImStreamService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用户级 IM SSE。
 *
 * <p>本副本只持有本副本的连接；消息提交后发布到 Redis topic {@code im:user:{userId}}，
 * 各副本订阅后投递本地 emitter。SSE 只做“有新数据”的提示，权威数据走 REST 补拉。
 */
@Slf4j
@Service
public class ImStreamServiceImpl implements ImStreamService {

    private static final String TOPIC_PREFIX = "im:user:";
    private static final long SSE_TIMEOUT_MS = 0L;

    private final RedissonClient eventBusClient;
    private final AgentMetrics metrics;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, Integer> listeners = new ConcurrentHashMap<>();

    public ImStreamServiceImpl(@Qualifier("eventBusRedissonClient") RedissonClient eventBusClient,
                               AgentMetrics metrics) {
        this.eventBusClient = eventBusClient;
        this.metrics = metrics;
    }

    @Override
    public SseEmitter register(Long userId) {
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        metrics.increment(AgentMetrics.IM_SSE_REGISTERED);
        ensureSubscribed(userId);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (Exception e) {
            remove(userId, emitter);
        }
        return emitter;
    }

    @Override
    public void publish(Long userId, ImStreamEvent event) {
        if (userId == null || event == null) {
            return;
        }
        try {
            topic(userId).publish(event);
        } catch (Exception e) {
            // Redis 不可用时降级为本地投递，至少让当前副本的页面能刷新
            log.warn("IM SSE publish failed, fallback to local: user={}, error={}", userId, e.getMessage());
            metrics.increment(AgentMetrics.IM_SSE_PUBLISH_FAILED);
            deliver(userId, event);
        }
    }

    /** 心跳；同时清理死连接。 */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        ImStreamEvent ping = ImStreamEvent.builder()
                .type(ImStreamEventType.HEARTBEAT)
                .timestamp(System.currentTimeMillis())
                .build();
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name(ImStreamEventType.HEARTBEAT).data(ping));
                } catch (Exception e) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void ensureSubscribed(Long userId) {
        listeners.computeIfAbsent(userId, id -> {
            RTopic topic = topic(id);
            return topic.addListener(ImStreamEvent.class, (channel, event) -> deliver(id, event));
        });
    }

    private void deliver(Long userId, ImStreamEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType() == null ? "message" : event.getType())
                        .data(event));
            } catch (Exception e) {
                remove(userId, emitter);
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(userId, list);
            }
        }
    }

    private RTopic topic(Long userId) {
        return eventBusClient.getTopic(TOPIC_PREFIX + userId,
                eventBusClient.getConfig().getCodec());
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<Long, Integer> entry : listeners.entrySet()) {
            try {
                topic(entry.getKey()).removeListener(entry.getValue());
            } catch (Exception ignored) {
                // best effort
            }
        }
        listeners.clear();
        emitters.clear();
    }
}
