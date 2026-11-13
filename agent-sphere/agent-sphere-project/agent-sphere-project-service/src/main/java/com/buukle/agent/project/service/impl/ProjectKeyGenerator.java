package com.buukle.agent.project.service.impl;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Generates a short, tenant-scoped project key from a date, a snowflake
 * suffix and a Redis counter scoped to the current millisecond.
 */
@Component
@RequiredArgsConstructor
public class ProjectKeyGenerator {

    private static final String PREFIX = "PRJ";
    private static final String SEQUENCE_KEY_PREFIX = "project:key:seq:";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyMMdd");
    private static final int SNOWFLAKE_SUFFIX_LENGTH = 4;
    private static final Duration SEQUENCE_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;

    public String next(Long tenantId) {
        long now = System.currentTimeMillis();
        String timestamp = TIMESTAMP_FORMATTER.format(LocalDateTime.now());
        RAtomicLong sequence = redissonClient.getAtomicLong(
                SEQUENCE_KEY_PREFIX + tenantId + ":" + now);
        long value = sequence.incrementAndGet();
        sequence.expire(SEQUENCE_TTL);
        String snowflake = Long.toString(IdUtil.getSnowflakeNextId(), 36)
                .toUpperCase();
        String suffix = snowflake.substring(
                Math.max(0, snowflake.length() - SNOWFLAKE_SUFFIX_LENGTH));
        return PREFIX + "-" + timestamp + "-" + suffix + "-" + value;
    }
}
