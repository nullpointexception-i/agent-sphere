package com.buukle.agent.runtime.orchestration.recovery;

import com.buukle.agent.common.event.InterruptedRunRecoveryEvent;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterruptedRunRecoveryCoordinator {

    private static final String LOCK_PREFIX = "run-recovery:";
    private static final long LOCK_LEASE_SECONDS = 60;
    private static final String CLAIM_PREFIX = "run-recovery:claimed:";

    private final RedissonClient redissonClient;
    private final RunSpi runSpi;
    private final RuntimeOrchestrator orchestrator;

    @EventListener
    public void onInterruptedRun(InterruptedRunRecoveryEvent event) {
        Long runId = event.runId();
        Long sessionId = event.sessionId();
        if (runId == null || sessionId == null) return;
        RBucket<String> claim = redissonClient.getBucket(CLAIM_PREFIX + runId);
        if (!claim.setIfAbsent("1", Duration.ofMinutes(2))) {
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_PREFIX + runId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) return;
            if (redissonClient.getBucket(
                    DistributedRuntimeConstants.sessionOwnerKey(sessionId)).isExists()) {
                return;
            }
            RunVO run = runSpi.getRun(runId);
            if (run == null || !RunStatus.RUNNING.name().equals(run.getStatus())) {
                return;
            }
            if (runSpi.existsRunAfterId(sessionId, runId)) {
                log.warn("Interrupted run {} has a newer run; skip recovery", runId);
                return;
            }
            redissonClient.getBucket(DistributedRuntimeConstants.sessionStateKey(sessionId)).delete();
            orchestrator.asyncResumeInterruptedRun(run, sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Interrupted run recovery scheduling failed: run={}", runId, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
