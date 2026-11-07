package com.buukle.agent.runtime.kernel.reconcile;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.event.InterruptedRunRecoveryEvent;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.SessionRunCoordinator;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
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
 * Reconciliation task for runs "stuck in RUNNING" (DB-level safety net).
 *
 * <p>Why {@link OrphanRunSweeper} cannot cover this: it only scans sessions whose Redis state ∈ {RUNNING, RUNNING_WITH_PENDING}
 * and whose owner lease has expired. But on the wrap-up path of runs like "the loop exited abnormally without writing a terminal state",
 * {@link SessionRunCoordinator} has already set state to IDLE and cleared the owner — producing
 * "RUNNING in the DB, IDLE/no owner in Redis", which neither of them sees (production runs 2263/2264 were exactly this).
 *
 * <p>Here we scan directly by the DB {@code updated_at}: still RUNNING beyond the threshold, and the session has no owner lease
 * (= no replica is running it) → mark FAILED and emit the terminal event. Whenever an owner exists it is skipped — a live replica renews every 60s,
 * so long-running tool/LLM calls are never killed by mistake.
 */
@Slf4j
@Component
public class StaleRunningRunReconciler {

    private static final String LOCK_KEY = "scheduler:stale-running-reconcile";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final int BATCH_LIMIT = 50;

    private static final long STALE_COMPACTION_MINUTES = 15;

    private final RedissonClient redissonClient;
    private final RunSpi runSpi;
    private final AgentCompactRecordSpi compactRecordSpi;
    private final SessionRunCoordinator coordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final long staleMinutes;
    private final long recoveryGraceMinutes;

    public StaleRunningRunReconciler(RedissonClient redissonClient, RunSpi runSpi,
                                     AgentCompactRecordSpi compactRecordSpi,
                                     SessionRunCoordinator coordinator,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${buukle.agent.run.stale-reconcile-minutes:30}") long staleMinutes,
                                     @Value("${buukle.agent.run.recovery-grace-minutes:2}") long recoveryGraceMinutes) {
        this.redissonClient = redissonClient;
        this.runSpi = runSpi;
        this.compactRecordSpi = compactRecordSpi;
        this.coordinator = coordinator;
        this.eventPublisher = eventPublisher;
        this.staleMinutes = staleMinutes;
        this.recoveryGraceMinutes = recoveryGraceMinutes;
    }

    @Scheduled(fixedDelayString = "${buukle.agent.run.stale-reconcile-interval:PT1M}")
    public void reconcile() {
        // The background thread may inherit the request thread's tenant, and DataPermissionInterceptor appends created_by to findStaleByStatus.
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.clear();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                return;
            }
            // Compaction records have no timer of their own: PENDING/RUNNING rows left behind by a process crash/restart are finalized here as well.
            int staleCompactions = compactRecordSpi.failStalePending(
                    LocalDateTime.now().minusMinutes(Math.max(STALE_COMPACTION_MINUTES, staleMinutes)));
            if (staleCompactions > 0) {
                log.warn("Marked {} stale compaction record(s) FAILED", staleCompactions);
            }
            recoverInterrupted();
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

    private void recoverInterrupted() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, recoveryGraceMinutes));
        List<Long> candidates = runSpi.findStaleRunIdsByStatus(
                RunStatus.RUNNING.name(), cutoff, BATCH_LIMIT);
        for (Long runId : candidates) {
            RunVO run = runSpi.getRunMeta(runId);
            if (run == null || !RunStatus.RUNNING.name().equals(run.getStatus())) {
                continue;
            }
            Long sessionId = run.getSessionId();
            if (sessionId == null
                    || redissonClient.getBucket(
                    DistributedRuntimeConstants.sessionOwnerKey(sessionId)).isExists()) {
                continue;
            }
            if (runSpi.existsRunAfterId(sessionId, runId)) {
                failRun(run, "interrupted by server restart and superseded by newer run");
                continue;
            }
            eventPublisher.publishEvent(new InterruptedRunRecoveryEvent(
                    sessionId, runId, "owner lease absent after restart"));
            log.warn("Interrupted run {} (session {}) queued for recovery", runId, sessionId);
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
        // findStaleByStatus is a snapshot: read again so a run that just completed is not hit by one extra blow.
        RunVO fresh = runSpi.getRunMeta(runId);
        if (fresh == null || !RunStatus.RUNNING.name().equals(fresh.getStatus())) {
            return;
        }
        failRun(fresh, RunnerConstants.RUN_STALE_MSG);
        log.warn("Stale RUNNING run {} (session {}) marked FAILED", runId, sessionId);
    }

    private void failRun(RunVO fresh, String reason) {
        Long runId = fresh.getId();
        Long sessionId = fresh.getSessionId();
        fresh.setStatus(RunStatus.FAILED.name());
        fresh.setErrorMessage(reason);
        runSpi.updateRun(fresh);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(runId)
                        .errorMessage(reason)
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId).build()));

        // If a dead run's session is still in RUNNING (replica crash scenario), reset it in passing; leftover queued input is handed to wake to continue.
        RBucket<String> stateBucket = redissonClient.getBucket(
                DistributedRuntimeConstants.sessionStateKey(sessionId));
        String state = stateBucket.get();
        if (SessionRunCoordinator.isRunningState(state)) {
            stateBucket.delete();
            var activeSessions = redissonClient.getSet(DistributedRuntimeConstants.KEY_ACTIVE_SESSIONS);
            if (activeSessions != null) activeSessions.remove(sessionId);
            coordinator.wake(sessionId);
        }
    }
}
