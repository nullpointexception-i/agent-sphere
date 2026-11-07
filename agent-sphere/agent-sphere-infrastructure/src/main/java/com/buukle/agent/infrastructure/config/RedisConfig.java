package com.buukle.agent.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedisConfig {

    /*
     * Note: Redis access in this project always goes through Redisson (see below); do not introduce
     * Spring Data Redis's StringRedisTemplate / RedisTemplate.
     *
     * Reason: this class reads its connection config from spring.redis.host/port (the legacy prefix,
     * read explicitly via @Value), while Spring Data Redis auto-configuration only binds
     * spring.data.redis.* (renamed in Boot 3). With only the legacy prefix configured,
     * auto-configuration silently falls back to localhost:6379 — there is no local Redis in the
     * container, so every read/write fails. This once caused task-level MCP credentials to never be
     * written, and the failure was downgraded to a warn and hard to locate.
     */

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Bean
    @Primary
    public RedissonClient redissonClient() {
        return createClient(null);
    }

    /**
     * Dedicated client for the event bus: JsonJacksonCodec (supports POJO serialization/deserialization
     * such as {@link com.buukle.agent.runtime.kernel.port.vo.EventType}). Separated from the default
     * client so the default codec used by CacheService/locks/RBucket is unaffected.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient eventBusRedissonClient() {
        return createClient(new JsonJacksonCodec());
    }

    private RedissonClient createClient(JsonJacksonCodec codec) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionPoolSize(32);
        if (codec != null) {
            config.setCodec(codec);
        }
        return Redisson.create(config);
    }
}
