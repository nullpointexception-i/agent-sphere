package com.buukle.agent.project.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectKeyGeneratorTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RAtomicLong sequence;
    @InjectMocks
    private ProjectKeyGenerator generator;

    @Test
    void includesTimestampSnowflakeAndRedisSequence() {
        when(redissonClient.getAtomicLong(anyString())).thenReturn(sequence);
        when(sequence.incrementAndGet()).thenReturn(7L);

        String key = generator.next(2L);

        assertTrue(key.matches("PRJ-\\d{6}-[A-Z0-9]{1,4}-7"), key);
        verify(sequence).expire(any(Duration.class));
    }
}
