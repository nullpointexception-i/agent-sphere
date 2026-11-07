package com.buukle.agent.infrastructure.service;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.persistence.SystemConfig;
import com.buukle.agent.infrastructure.persistence.SystemConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * System config: Redis cache (RBucket + 5 min TTL), globally consistent.
 * There are two AES key generation points ({@link #get} on a cache miss, and {@link #init}), both going
 * through the Redis {@code SET NX} atomic claim: only the replica that wins the claim generates and
 * persists the key; the others read it back from the DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigSpi {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String CACHE_KEY_PREFIX = "syscfg:";

    private final SystemConfigMapper systemConfigMapper;
    private final RedissonClient redissonClient;

    @Override
    public String get(String key) {
        return get(key, null);
    }

    @Override
    public String get(String key, String defaultValue) {
        RBucket<String> bucket = bucket(key);
        String cached = bucket.get();
        if (cached != null) {
            return cached;
        }
        SystemConfig config = systemConfigMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, key));
        if (config == null) {
            return defaultValue;
        }
        String value = config.getConfigValue();
        if (value == null) {
            value = "";
        }
        if (SystemConfigKeys.AES_KEY.equals(key) && value.isBlank()) {
            value = initAesKey();
        }
        // Write the cache only for non-empty values: empty values are neither cached nor TTL-renewed, so a
        // stale empty cache does not linger after the DB is changed directly via SQL
        if (!value.isBlank()) {
            bucket.set(value);
            bucket.expire(CACHE_TTL);
        }
        return value;
    }

    @Override
    public void set(String key, String value) {
        SystemConfig config = systemConfigMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, key));
        if (config != null) {
            config.setConfigValue(value);
            systemConfigMapper.updateById(config);
        }
        RBucket<String> bucket = bucket(key);
        bucket.set(value);
        bucket.expire(CACHE_TTL);
    }

    public void invalidateCache(String key) {
        bucket(key).delete();
    }

    public List<SystemConfig> listAll() {
        return systemConfigMapper.selectList(null);
    }

    /**
     * First startup of the AES-256 key: an atomic Redis {@code SET NX} claim, so only the replica that wins
     * generates and persists the key; the others read it back from the DB (the claim key carries a short TTL
     * to avoid a lingering claim).
     */
    private String initAesKey() {
        RBucket<String> initLock = redissonClient.getBucket(DistributedRuntimeConstants.KEY_AES_KEY_INIT);
        boolean acquired = initLock.trySet("1", 1, java.util.concurrent.TimeUnit.MINUTES);
        if (!acquired) {
            // Another replica is generating it: read back from the DB (wait up to 5s)
            for (int i = 0; i < 5; i++) {
                SystemConfig config = systemConfigMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SystemConfig>()
                                .eq(SystemConfig::getConfigKey, SystemConfigKeys.AES_KEY));
                if (config != null && config.getConfigValue() != null && !config.getConfigValue().isBlank()) {
                    return config.getConfigValue();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("AES key init lock not acquired and DB value unavailable, generating locally");
        }
        String generated = generateAesKey();
        SystemConfig config = systemConfigMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getConfigKey, SystemConfigKeys.AES_KEY));
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey(SystemConfigKeys.AES_KEY);
            config.setConfigValue(generated);
            systemConfigMapper.insert(config);
        } else if (config.getConfigValue() == null || config.getConfigValue().isBlank()) {
            config.setConfigValue(generated);
            systemConfigMapper.updateById(config);
        } else {
            generated = config.getConfigValue();
        }
        return generated;
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(CACHE_KEY_PREFIX + key);
    }

    private static String generateAesKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    @PostConstruct
    public void init() {
        // Clear empty-value cache left over from direct SQL changes to the DB (e.g. after V62 fills
        // user.resource-template from empty to the template)
        invalidateCache(SystemConfigKeys.USER_RESOURCE_TEMPLATE);
        get(SystemConfigKeys.AES_KEY);
    }
}
