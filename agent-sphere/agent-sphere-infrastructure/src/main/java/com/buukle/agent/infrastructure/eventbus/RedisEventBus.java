package com.buukle.agent.infrastructure.eventbus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Redis pub/sub event bus (Redisson {@link RTopic} + JsonJacksonCodec).
 * The only path for multi-replica event delivery: the publisher publishes, and each replica (including the
 * publisher itself) subscribes and delivers locally.
 * The payload is any Jackson-serializable POJO; on subscribe it is deserialized to the concrete type.
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
