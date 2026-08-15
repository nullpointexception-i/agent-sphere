package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话运行协调器（分布式）：
 * - 状态机（IDLE / RUNNING / RUNNING_WITH_PENDING）与 owner 租约落 Redis，副本可互换；
 * - run 执行锚定单一副本（owner），执行中每 60s 续约租约；
 * - in-flight loop 瞬态留在执行副本（决策 A），输入队列/取消语义存活，可被接管。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRunCoordinator {

    private static final long OWNER_RENEW_INTERVAL_SECONDS = 60;

    private final SessionInputManager inputManager;
    private final SessionRunner sessionRunner;
    private final RedissonClient redissonClient;
    private final AgentRuntimeProperties properties;

    private static final String REPLICA_ID = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ownerRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "owner-lease-renewer");
        t.setDaemon(true);
        return t;
    });

    public void wake(Long sessionId, KernelContext ctx, Long runId) {
        if (ctx != null) {
            sessionRunner.registerContext(sessionId, ctx);
        }
        if (runId != null) {
            sessionRunner.setPendingRun(sessionId, runId);
        }
        wake(sessionId);
    }

    public void wake(Long sessionId) {
        RLock lock = lock(sessionId);
        lock.lock();
        try {
            String state = stateBucket(sessionId).get();
            if (state == null || State.IDLE.name().equals(state)) {
                stateBucket(sessionId).set(State.RUNNING.name());
                setOwner(sessionId);
                submitRun(sessionId);
                return;
            }
            if (State.RUNNING.name().equals(state) && inputManager.hasPending(sessionId)) {
                stateBucket(sessionId).set(State.RUNNING_WITH_PENDING.name());
            }
        } finally {
            lock.unlock();
        }
    }

    private void submitRun(Long sessionId) {
        executor.submit(() -> {
            ScheduledFuture<?> renewal = ownerRenewer.scheduleAtFixedRate(
                    () -> setOwner(sessionId), OWNER_RENEW_INTERVAL_SECONDS,
                    OWNER_RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
            try {
                sessionRunner.run(sessionId);
            } catch (Exception e) {
                log.error("Session runner failed for session {}", sessionId, e);
            } finally {
                renewal.cancel(true);
                RLock lock = lock(sessionId);
                lock.lock();
                try {
                    if (inputManager.hasPending(sessionId)) {
                        stateBucket(sessionId).set(State.RUNNING.name());
                        setOwner(sessionId);
                        submitRun(sessionId);
                    } else {
                        stateBucket(sessionId).set(State.IDLE.name());
                        clearOwner(sessionId);
                    }
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    private void setOwner(Long sessionId) {
        RBucket<String> owner = redissonClient.getBucket(DistributedRuntimeConstants.sessionOwnerKey(sessionId));
        owner.set(REPLICA_ID);
        owner.expire(properties.getDistributed().getOwnerLease());
    }

    private void clearOwner(Long sessionId) {
        redissonClient.getBucket(DistributedRuntimeConstants.sessionOwnerKey(sessionId)).delete();
    }

    private RBucket<String> stateBucket(Long sessionId) {
        return redissonClient.getBucket(DistributedRuntimeConstants.sessionStateKey(sessionId));
    }

    private RLock lock(Long sessionId) {
        return redissonClient.getLock(DistributedRuntimeConstants.sessionLockKey(sessionId));
    }

    enum State {
        IDLE, RUNNING, RUNNING_WITH_PENDING
    }
}
