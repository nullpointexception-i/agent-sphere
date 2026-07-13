package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.infrastructure.persistence.SystemConfig;
import com.buukle.agent.infrastructure.persistence.SystemConfigMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigSpi {

    private final SystemConfigMapper systemConfigMapper;

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100)
            .build();

    @Override
    public String get(String key) {
        return get(key, null);
    }

    @Override
    public String get(String key, String defaultValue) {
        String cached = cache.getIfPresent(key);
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
        if (SystemConfigKeys.AES_KEY.equals(key) && (value == null || value.isBlank())) {
            value = generateAesKey();
            config.setConfigValue(value);
            systemConfigMapper.updateById(config);
            log.info("Auto-generated and persisted AES-256 key");
        }
        cache.put(key, value);
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
        cache.put(key, value);
    }

    public void invalidateCache(String key) {
        cache.invalidate(key);
    }

    public List<SystemConfig> listAll() {
        return systemConfigMapper.selectList(null);
    }

    private static String generateAesKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    @PostConstruct
    public void init() {
        get(SystemConfigKeys.AES_KEY);
    }
}
