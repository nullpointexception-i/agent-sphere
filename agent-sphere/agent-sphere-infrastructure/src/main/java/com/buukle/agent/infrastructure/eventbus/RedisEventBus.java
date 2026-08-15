package com.buukle.agent.infrastructure.eventbus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Redis pub/sub 事件总线（Redisson {@link RTopic} + JsonJacksonCodec）。
 * 多副本事件投递的唯一路径：发布方 publish，各副本（含发布者自己）subscribe 后投本地。
 * 载荷为任意 Jackson 可序列化 POJO；订阅时按具体类型反序列化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisEventBus {

    private final RedissonClient eventBusRedissonClient;

    public <T> void publish(String topic, T payload) {
        try {
            RTopic rTopic = topic(topic);
            rTopic.publish(payload);
        } catch (Exception e) {
            log.warn("RedisEventBus publish failed on topic {}: {}", topic, e.getMessage());
        }
    }

    public <T> void subscribe(String topic, Class<T> payloadType, Consumer<T> consumer) {
        try {
            RTopic rTopic = topic(topic);
            rTopic.addListener(payloadType, (channel, message) -> {
                try {
                    consumer.accept(message);
                } catch (Exception e) {
                    log.warn("RedisEventBus listener error on topic {}: {}", topic, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("RedisEventBus subscribe failed on topic {}: {}", topic, e.getMessage());
        }
    }

    private RTopic topic(String name) {
        return eventBusRedissonClient.getTopic(name, eventBusRedissonClient.getConfig().getCodec());
    }
}
