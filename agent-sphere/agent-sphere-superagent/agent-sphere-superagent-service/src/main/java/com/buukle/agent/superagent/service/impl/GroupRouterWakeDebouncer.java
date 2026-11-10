package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SuperAgentTraceContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces realtime Group Router wakes into one run per thread per debounce window.
 *
 * <p>A message-per-run pattern wastes an LLM call for every chat line; waiting a short window keeps
 * the batch semantics while staying far below the compensation interval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupRouterWakeDebouncer {

    private final GroupRouterScheduler scheduler;
    private final RedissonClient redissonClient;

    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "group-router-debounce");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${buukle.agent.super-agent.group-router.realtime.debounce-ms:500}")
    private long debounceMs;

    public void schedule(Long threadId) {
        if (threadId == null) {
            return;
        }
        pending.compute(threadId, (id, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return timer.schedule(() -> fire(id), Math.max(0, debounceMs), TimeUnit.MILLISECONDS);
        });
    }

    private void fire(Long threadId) {
        pending.remove(threadId);
        if (!scheduler.hasPending(threadId)) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        RLock lock = redissonClient.getLock(GroupRouterScheduler.LOCK_PREFIX + threadId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, GroupRouterScheduler.LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            scheduler.processThreadNow(threadId);
        } catch (Exception e) {
            log.warn("Group router debounce wake failed for thread {}: {}", threadId, e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            SuperAgentTraceContext.clear();
            TenantScopeHolder.clear();
            TenantUtil.stop();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
