package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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

    @Scheduled(fixedDelayString = "${buukle.agent.distributed.orphan-sweep-interval:PT30S}")
    public void sweep() {
        for (String key : redissonClient.getKeys().getKeysByPattern(
                DistributedRuntimeConstants.KEY_STATE_PREFIX + "*")) {
            String suffix = key.substring(DistributedRuntimeConstants.KEY_STATE_PREFIX.length());
            try {
                sweepSession(Long.valueOf(suffix));
            } catch (NumberFormatException e) {
                // 非会话键，忽略
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
            log.warn("Orphan run detected for session {} (owner lease expired), marking FAILED", sessionId);
            RunVO active = runSpi.findActiveRun(sessionId);
            if (active != null) {
                flushReasoning(active);
                active.setStatus(RunStatus.FAILED.name());
                runSpi.updateRun(active);
            }
            stateBucket.delete();
            coordinator.wake(sessionId);
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
