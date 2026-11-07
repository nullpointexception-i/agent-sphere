package com.buukle.agent.runtime.kernel.runner.support;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * Redis-backed cancellation flags for runs and sessions.
 *
 * <p>Kept as an infrastructure adapter so the loop/turn/tool services never touch Redisson directly.
 */
@Component
@RequiredArgsConstructor
public class RunCancellationStore {

    /** Placeholder marker for the Redis RSet (used as a boolean flag). */
    private static final Object CANCEL_MARKER = Boolean.TRUE;

    private final RedissonClient redissonClient;

    public void cancelRun(Long runId) {
        if (runId != null) {
            runCancelSet(runId).add(CANCEL_MARKER);
        }
    }

    public void cancelSession(Long sessionId) {
        if (sessionId != null) {
            sessionCancelSet(sessionId).add(CANCEL_MARKER);
        }
    }

    public void clearSessionCancelled(Long sessionId) {
        if (sessionId != null) {
            sessionCancelSet(sessionId).delete();
        }
    }

    public boolean hasActiveRun(Long sessionId) {
        return sessionId != null && redissonClient.getBucket(
                DistributedRuntimeConstants.sessionOwnerKey(sessionId)).isExists();
    }

    public boolean isRunCancelled(Long runId) {
        return runId != null && runCancelSet(runId).contains(CANCEL_MARKER);
    }

    public boolean isSessionCancelled(Long sessionId) {
        return sessionId != null && sessionCancelSet(sessionId).contains(CANCEL_MARKER);
    }

    public void clearRunCancelled(Long runId) {
        if (runId != null) {
            runCancelSet(runId).delete();
        }
    }

    private RSet<Object> runCancelSet(Long runId) {
        return redissonClient.getSet(DistributedRuntimeConstants.runCancelKey(runId));
    }

    private RSet<Object> sessionCancelSet(Long sessionId) {
        return redissonClient.getSet(DistributedRuntimeConstants.sessionCancelKey(sessionId));
    }
}
