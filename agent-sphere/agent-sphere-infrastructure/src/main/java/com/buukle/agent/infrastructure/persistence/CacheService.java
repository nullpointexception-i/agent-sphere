package com.buukle.agent.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CacheService {
    private final RedissonClient redissonClient;

    public void set(String key, Object value, long ttlMinutes) {
        RBucket<Object> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttlMinutes, TimeUnit.MINUTES);
    }

    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    public void delete(String key) {
        redissonClient.getBucket(key).delete();
    }

    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }
}
