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
 * Session run coordinator (distributed):
 * - state machine (IDLE / RUNNING / RUNNING_WITH_PENDING) and owner lease live in Redis, so replicas are interchangeable;
 * - run execution is anchored to a single replica (owner), which renews the lease every 60s;
 * - the in-flight loop's transient state stays on the executing replica (decision A); input queue/cancel semantics survive and can be taken over.
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
                activeSessions().add(sessionId);
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
                    // Already cancelled: drop queued messages and go to IDLE; do not restart the main loop for pending input
                    if (inputManager.hasPending(sessionId) && !sessionRunner.isSessionCancelled(sessionId)) {
                        stateBucket(sessionId).set(State.RUNNING.name());
                        setOwner(sessionId);
                        submitRun(sessionId);
                    } else {
                        stateBucket(sessionId).set(State.IDLE.name());
                        activeSessions().remove(sessionId);
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

    private org.redisson.api.RSet<Long> activeSessions() {
        return redissonClient.getSet(DistributedRuntimeConstants.KEY_ACTIVE_SESSIONS);
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
