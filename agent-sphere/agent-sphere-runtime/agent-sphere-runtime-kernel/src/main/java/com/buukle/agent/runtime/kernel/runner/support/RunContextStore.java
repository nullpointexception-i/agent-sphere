package com.buukle.agent.runtime.kernel.runner.support;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed run context and pending-run pointers.
 *
 * <p>Long runs renew the context TTL every loop so tools do not silently become empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunContextStore {

    private static final Duration CONTEXT_TTL = Duration.ofMinutes(30);

    private final RedissonClient redissonClient;

    public void registerContext(Long sessionId, KernelContext ctx) {
        ctxCache().put(sessionId, ctx, CONTEXT_TTL.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void setPendingRun(Long sessionId, Long runId) {
        if (runId != null) {
            redissonClient.<Long>getBucket(
                    DistributedRuntimeConstants.sessionPendingRunKey(sessionId)).set(runId);
        }
    }

    /** Reads and removes the pending run pointer. */
    public Long consumePendingRun(Long sessionId) {
        return redissonClient.<Long>getBucket(
                DistributedRuntimeConstants.sessionPendingRunKey(sessionId)).getAndDelete();
    }

    /** Evicts the run context when the loop exits. */
    public void evictContext(Long sessionId) {
        ctxCache().remove(sessionId);
    }

    public KernelContext getContext(Long sessionId) {        KernelContext ctx = ctxCache().get(sessionId);
        if (ctx != null) {
            // Long runs can last hours: renew every loop so the 30-minute TTL does not empty tools.
            try {
                ctxCache().put(sessionId, ctx, CONTEXT_TTL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Failed to refresh KernelContext TTL for session {}: {}", sessionId, e.getMessage());
            }
        }
        return ctx;
    }

    private RMapCache<Long, KernelContext> ctxCache() {
        return redissonClient.getMapCache(DistributedRuntimeConstants.KEY_CTX);
    }
}
