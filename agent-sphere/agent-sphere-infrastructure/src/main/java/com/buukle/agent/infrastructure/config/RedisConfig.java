package com.buukle.agent.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.Kryo5Codec;
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
     * such as {@link com.buukle.agent.common.event.EventType}). Separated from the default
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
        } else {
            // Bind the Kryo class resolver to the application class loader. The default codec
            // resolves class names with its own loader; under spring-boot-devtools that is the
            // parent loader, so a decoded event would be a different Class object than the
            // application's own (identical name, different loader) and every listener cast fails
            // silently. In a normal (non-devtools) launch this is the same loader as before.
            config.setCodec(new Kryo5Codec(RedisConfig.class.getClassLoader()));
        }
        return Redisson.create(config);
    }
}
