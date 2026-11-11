package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.superagent.repository.AgentLocalTaskDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/** Expires local tasks whose desktop lease was not renewed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalTaskSweeper {

    private static final String LOCK_KEY = "scheduler:local-task-sweep";
    private static final long LOCK_LEASE_SECONDS = 60;

    private final AgentLocalTaskMapper taskMapper;
    private final AgentLocalTaskDeliveryMapper deliveryMapper;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.super-agent.local.sweep.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.local.sweep-interval-ms:60000}")
    public void run() {
        if (!enabled) return;
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) return;
            int expired = taskMapper.expireStale(LocalDateTime.now());
            if (expired > 0) {
                deliveryMapper.markExpiredDeliveries();
                log.warn("Expired {} local tasks without a live desktop lease", expired);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Local task sweep failed: {}", e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
