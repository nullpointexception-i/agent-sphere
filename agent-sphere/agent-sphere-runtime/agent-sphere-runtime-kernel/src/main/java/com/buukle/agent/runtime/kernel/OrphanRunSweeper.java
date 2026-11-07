package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.event.InterruptedRunRecoveryEvent;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orphan run sweep (runs on every replica): the session is in RUNNING/RUNNING_WITH_PENDING but the owner lease
 * has expired (the executing replica is down) → acquire the lock → mark the session's active run as FAILED → reset state → continue the remaining queued input.
 * Responsibility boundary: this only handles SessionRunner run terminal states; task-level progression belongs to the task poller, and the two never double-write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanRunSweeper {

    private final RedissonClient redissonClient;
    private final RunSpi runSpi;
    private final ReasoningBufferStore reasoningBufferStore;
    private final SessionRunCoordinator coordinator;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${buukle.agent.distributed.orphan-sweep-interval:PT30S}")
    public void sweep() {
        for (Long sessionId : redissonClient.<Long>getSet(
                DistributedRuntimeConstants.KEY_ACTIVE_SESSIONS).readAll()) {
            try {
                sweepSession(sessionId);
            } catch (Exception e) {
                log.warn("Orphan sweep failed for session {}", sessionId, e);
            }
        }
    }

    private void sweepSession(Long sessionId) {
        RBucket<String> stateBucket = redissonClient.getBucket(
                DistributedRuntimeConstants.sessionStateKey(sessionId));
        String state = stateBucket.get();
        if (state == null || !isActiveState(state)) {
            return;
        }
        RBucket<String> owner = redissonClient.getBucket(
                DistributedRuntimeConstants.sessionOwnerKey(sessionId));
        if (owner.isExists()) {
            return; // the executing replica is alive
        }
        RLock lock = redissonClient.getLock(DistributedRuntimeConstants.sessionLockKey(sessionId));
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (owner.isExists()) {
                return;
            }
            log.warn("Orphan run detected for session {} (owner lease expired)", sessionId);
            RunVO active = runSpi.findActiveRun(sessionId);
            if (active != null) {
                flushReasoning(active);
                if (runSpi.existsRunAfterId(sessionId, active.getId())) {
                    active.setStatus(RunStatus.FAILED.name());
                    active.setErrorMessage("interrupted by server restart and superseded by newer run");
                    runSpi.updateRun(active);
                    eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(active.getId())
                                    .assistantReply(active.getErrorMessage())
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + active.getId()).build()));
                    stateBucket.delete();
                    var activeSessions = redissonClient.getSet(DistributedRuntimeConstants.KEY_ACTIVE_SESSIONS);
                    if (activeSessions != null) activeSessions.remove(sessionId);
                    coordinator.wake(sessionId);
                } else {
                    // The run was not updated: leave it to recovery to restore the original run; do not mark FAILED here.
                    eventPublisher.publishEvent(new InterruptedRunRecoveryEvent(
                            sessionId, active.getId(), "owner lease expired"));
                    log.warn("Interrupted run {} (session {}) queued for recovery", active.getId(), sessionId);
                }
            } else {
                stateBucket.delete();
                var activeSessions = redissonClient.getSet(DistributedRuntimeConstants.KEY_ACTIVE_SESSIONS);
                    if (activeSessions != null) activeSessions.remove(sessionId);
                coordinator.wake(sessionId);
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean isActiveState(String state) {
        return SessionRunCoordinator.State.RUNNING.name().equals(state)
                || SessionRunCoordinator.State.RUNNING_WITH_PENDING.name().equals(state);
    }

    /** Flush this replica's in-memory reasoning buffer to the run so an orphan run's reasoning is not lost. */
    private void flushReasoning(RunVO run) {
        try {
            String reasoning = reasoningBufferStore.drain(run.getId());
            if (reasoning != null) {
                run.setReasoning(reasoning);
            }
        } catch (Exception e) {
            log.warn("Failed to flush reasoning for run {} during orphan sweep", run.getId(), e);
        }
    }
}
