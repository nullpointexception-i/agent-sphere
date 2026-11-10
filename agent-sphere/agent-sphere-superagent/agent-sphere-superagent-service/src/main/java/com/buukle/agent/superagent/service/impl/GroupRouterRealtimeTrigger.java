package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SuperAgentTraceContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.superagent.service.event.GroupRouterWakeEvent;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Real-time Group Router wake-up.
 *
 * <p>When a group event is committed and no router run is active, the thread is processed
 * immediately instead of waiting for the 3-second batch window. If a run is already in flight,
 * {@link GroupRouterScheduler} keeps the events and processes them after the run finishes
 * (compensation path).
 */
@Slf4j
@Component
public class GroupRouterRealtimeTrigger {

    private final GroupRouterScheduler scheduler;
    private final RedissonClient redissonClient;
    private final Executor executor;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.group-router.enabled:false}")
    private boolean groupRouterEnabled;

    public GroupRouterRealtimeTrigger(
            GroupRouterScheduler scheduler,
            RedissonClient redissonClient,
            @Qualifier("runtimeAsyncExecutor") Executor executor) {
        this.scheduler = scheduler;
        this.redissonClient = redissonClient;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWake(GroupRouterWakeEvent event) {
        if (!superAgentEnabled || !groupRouterEnabled
                || event == null || event.threadId() == null) {
            return;
        }
        Long threadId = event.threadId();
        executor.execute(() -> process(threadId));
    }

    private void process(Long threadId) {
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        RLock lock = redissonClient.getLock(GroupRouterScheduler.LOCK_PREFIX + threadId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0,
                    GroupRouterScheduler.LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            scheduler.processThreadNow(threadId);
        } catch (Exception e) {
            // The compensation scheduler retries on its next tick.
            log.warn("Realtime group router wake failed for thread {}: {}", threadId, e.getMessage());
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
}
