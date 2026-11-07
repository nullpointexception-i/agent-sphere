package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * "Stuck in WAITING" reconciliation task (safety net).
 *
 * <p>Wake paths always "CAS to RESUMED first, then resume"; any silent failure (resume throws, event lost,
 * replica restart losing in-memory timers, cron failing as well) can leave a run in WAITING forever. This task periodically scans
 * for runs "still WAITING past the timeout with no PENDING wait record left" and pushes each one once more; on failure it emits a terminal event,
 * guaranteeing a run never stays stuck "running" forever.
 */
@Slf4j
@Component
public class WaitingRunReconciler {

    private static final String LOCK_KEY = "scheduler:wait-reconcile";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final int BATCH_LIMIT = 50;

    private final RunSpi runSpi;
    private final PendingWaitSpi pendingWaitSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final RedissonClient redissonClient;

    /** "Stuck" threshold: greater than MAX_WAIT_MS (10min), so a legitimately long wait is not rescued prematurely. */
    @Value("${buukle.agent.wait.reconcile-stale-minutes:11}")
    private long staleMinutes;

    public WaitingRunReconciler(RunSpi runSpi, PendingWaitSpi pendingWaitSpi,
                                ChatRuntimeService chatRuntimeService, RedissonClient redissonClient) {
        this.runSpi = runSpi;
        this.pendingWaitSpi = pendingWaitSpi;
        this.chatRuntimeService = chatRuntimeService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelayString = "${buukle.agent.wait.reconcile-interval:PT30S}")
    public void reconcile() {
        // Reconciliation is a background action: clear tenant context possibly left on the scheduling thread, otherwise findStaleByStatus gets filtered by created_by.
        PendingWaitWakeScheduler.clearInheritedContext();
        boolean locked = false;
        try {
            locked = redissonClient.getLock(LOCK_KEY).tryLock(0, LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, staleMinutes));
            List<RunVO> stale = runSpi.findStaleByStatus(RunStatus.WAITING.name(), cutoff, BATCH_LIMIT);
            if (stale.isEmpty()) {
                return;
            }
            log.warn("Waiting-run reconcile: {} stale WAITING run(s)", stale.size());
            for (RunVO run : stale) {
                try {
                    List<PendingWaitVO> pending = pendingWaitSpi.listPendingByRunId(run.getId());
                    if (!pending.isEmpty()) {
                        continue; // pending wake-ups remain -> the normal path will handle them
                    }
                    rescue(run);
                } catch (Exception e) {
                    log.error("Reconcile failed for run {}", run.getId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Waiting-run reconcile sweep failed", e);
        } finally {
            if (locked) {
                try {
                    if (redissonClient.getLock(LOCK_KEY).isHeldByCurrentThread()) {
                        redissonClient.getLock(LOCK_KEY).unlock();
                    }
                } catch (Exception e) {
                    log.warn("Failed to release reconcile lock", e);
                }
            }
        }
    }

    /** The record was consumed but the run did not resume → push it once more (without relying on a PENDING record); on failure mark it terminal. */
    private void rescue(RunVO run) {
        PendingWaitVO synthetic = new PendingWaitVO();
        synthetic.setSessionId(run.getSessionId());
        synthetic.setRunId(run.getId());
        synthetic.setWaitMs(0);
        synthetic.setStatus(RunStatus.WAITING.name());
        // Keep the original suspension kind: LLM_RETRY must not take wait's synthetic-message resume path
        synthetic.setKind(run.getWaitKind());
        ChatRuntimeService.ResumeOutcome outcome = chatRuntimeService.resumeFromWait(synthetic);
        if (outcome != ChatRuntimeService.ResumeOutcome.RESUMED) {
            chatRuntimeService.failStuckWait(run.getSessionId(), run.getId(), "reconcile rescue: " + outcome);
        } else {
            log.info("Reconciled stuck WAITING run {}", run.getId());
        }
    }
}