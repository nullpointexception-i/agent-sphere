package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 「卡在 RUNNING」的 run 对账任务（DB 层安全网）。
 *
 * <p>为什么 {@link OrphanRunSweeper} 兜不住：它只扫 Redis 里 state ∈ {RUNNING, RUNNING_WITH_PENDING}
 * 且 owner 租约过期的 session。而「循环异常退出但没写终态」这类 run 的收尾路径里，
 * {@link SessionRunCoordinator} 已经把 state 置 IDLE 并清掉 owner —— 组合成了
 * 「DB 里 RUNNING、Redis 里 IDLE/无 owner」，两者都看不见（线上 run 2263/2264 即此）。
 *
 * <p>这里直接按 DB 的 {@code updated_at} 扫：超过阈值仍 RUNNING，且 session 无 owner 租约
 * （= 没有任何副本在跑它）→ 置 FAILED 并发终态事件。owner 存在时一律跳过 —— 活副本会每 60s 续约，
 * 因此长时间的工具/LLM 调用不会被误杀。
 */
@Slf4j
@Component
public class StaleRunningRunReconciler {

    private static final String LOCK_KEY = "scheduler:stale-running-reconcile";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final int BATCH_LIMIT = 50;

    private final RedissonClient redissonClient;
    private final RunSpi runSpi;
    private final SessionRunCoordinator coordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final long staleMinutes;

    public StaleRunningRunReconciler(RedissonClient redissonClient, RunSpi runSpi,
                                     SessionRunCoordinator coordinator,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${buukle.agent.run.stale-reconcile-minutes:30}") long staleMinutes) {
        this.redissonClient = redissonClient;
        this.runSpi = runSpi;
        this.coordinator = coordinator;
        this.eventPublisher = eventPublisher;
        this.staleMinutes = staleMinutes;
    }

    @Scheduled(fixedDelayString = "${buukle.agent.run.stale-reconcile-interval:PT1M}")
    public void reconcile() {
        // 后台线程可能继承请求线程的租户，DataPermissionInterceptor 会给 findStaleByStatus 追加 created_by。
        TenantUtil.stop();
        AuthContext.clear();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, staleMinutes));
            List<RunVO> stale = runSpi.findStaleByStatus(RunStatus.RUNNING.name(), cutoff, BATCH_LIMIT);
            if (stale.isEmpty()) {
                return;
            }
            log.warn("Stale RUNNING reconcile: {} candidate run(s) untouched for over {} min",
                    stale.size(), staleMinutes);
            for (RunVO run : stale) {
                try {
                    rescue(run);
                } catch (Exception e) {
                    log.error("Stale RUNNING reconcile failed for run {}", run.getId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Stale RUNNING reconcile sweep failed", e);
        } finally {
            if (locked) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("Failed to release stale-run reconcile lock", e);
                }
            }
        }
    }

    private void rescue(RunVO candidate) {
        Long runId = candidate.getId();
        Long sessionId = candidate.getSessionId();
        if (runId == null || sessionId == null) {
            return;
        }
        if (redissonClient.getBucket(DistributedRuntimeConstants.sessionOwnerKey(sessionId)).isExists()) {
            log.info("Run {} is stale in DB but session {} still has a live owner; skipping", runId, sessionId);
            return;
        }
        // findStaleByStatus 是快照：重新读一次，避免给刚完成的 run 补一刀。
        RunVO fresh = runSpi.getRun(runId);
        if (fresh == null || !RunStatus.RUNNING.name().equals(fresh.getStatus())) {
            return;
        }
        fresh.setStatus(RunStatus.FAILED.name());
        fresh.setErrorMessage(RunnerConstants.RUN_STALE_MSG);
        runSpi.updateRun(fresh);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setErrorMessage(RunnerConstants.RUN_STALE_MSG)
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId)));

        // 死 run 的 session 若还停在 RUNNING（副本崩溃场景），顺手复位；残留排队输入交给 wake 继续跑。
        RBucket<String> stateBucket = redissonClient.getBucket(
                DistributedRuntimeConstants.sessionStateKey(sessionId));
        String state = stateBucket.get();
        if (SessionRunCoordinator.State.RUNNING.name().equals(state)
                || SessionRunCoordinator.State.RUNNING_WITH_PENDING.name().equals(state)) {
            stateBucket.delete();
            coordinator.wake(sessionId);
        }
        log.warn("Stale RUNNING run {} (session {}) marked FAILED", runId, sessionId);
    }
}
