package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.event.InterruptedRunRecoveryEvent;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
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
 * 孤儿 run 清扫（各副本运行）：session 处于 RUNNING/RUNNING_WITH_PENDING 但 owner 租约
 * 过期（执行副本宕机）→ 抢锁 → 将该 session 活动 run 置 FAILED → 状态复位 → 续跑队列剩余输入。
 * 职责边界：只处理 SessionRunner 的 run 终态；任务级推进由 task poller 负责，二者不双写。
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
            return; // 执行副本存活
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
                            new RuntimeEventDataVO()
                                    .setSessionId(sessionId)
                                    .setRunId(active.getId())
                                    .setAssistantReply(active.getErrorMessage())
                                    .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + active.getId())));
                    stateBucket.delete();
                    var activeSessions = redissonClient.getSet(DistributedRuntimeConstants.KEY_ACTIVE_SESSIONS);
                    if (activeSessions != null) activeSessions.remove(sessionId);
                    coordinator.wake(sessionId);
                } else {
                    // 没有更新 run：交给 recovery 恢复原 run；不要在这里 FAILED。
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

    /** 清空本副本内存中的推理缓冲并写回 run，避免孤儿 run 的推理丢失。 */
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
