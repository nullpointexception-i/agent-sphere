package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compensation path for wait wake-up: fallback when in-memory timers are lost (replica restart/deploy).
 *
 * <p>Shares the {@code claimForResume} CAS with {@link PendingWaitWakeScheduler},
 * so even if both hit the same record it is resumed only once — no "who goes first" protocol is needed here.
 *
 * <p>In k8s the backend runs multiple replicas, so the sweep must hold a Redisson lock to avoid every replica grabbing the same batch
 * (CAS prevents duplicate resumes, but event broadcasts would still duplicate).
 */
@Slf4j
@Component
public class WaitWakeTask {

    private static final String WAKE_LOCK_KEY = "scheduler:pending-wait";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    /** Allowed sweep lag: in-memory timers are the primary path, the cron is only a fallback, so a few seconds of jitter is tolerated. */
    private static final Duration OVERDUE_GRACE = Duration.ofSeconds(2);
    private static final int BATCH_LIMIT = 100;

    private final PendingWaitSpi pendingWaitSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final RedissonClient redissonClient;

    public WaitWakeTask(PendingWaitSpi pendingWaitSpi,
                        ChatRuntimeService chatRuntimeService,
                        RedissonClient redissonClient) {
        this.pendingWaitSpi = pendingWaitSpi;
        this.chatRuntimeService = chatRuntimeService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(cron = "${buukle.agent.wait.wake-cron:*/10 * * * * ?}")
    public void sweepDueWaits() {
        // The scheduling thread may retain tenant context from another task; with a tenant set, DataPermission filters out this batch.
        PendingWaitWakeScheduler.clearInheritedContext();
        boolean locked = false;
        try {
            locked = redissonClient.getLock(WAKE_LOCK_KEY)
                    .tryLock(0, LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                return;
            }
            List<PendingWaitVO> due = pendingWaitSpi.listDue(OVERDUE_GRACE, BATCH_LIMIT);
            if (due.isEmpty()) {
                return;
            }
            log.info("Wait wake sweep: {} due record(s)", due.size());
            for (PendingWaitVO wait : due) {
                if (!pendingWaitSpi.claimForResume(wait.getId())) {
                    continue;
                }
                try {
                    PendingWaitVO claimed = pendingWaitSpi.getById(wait.getId());
                    if (claimed == null) {
                        // Claimed successfully but unreadable: restore PENDING for the next round; never stop here.
                        log.warn("Wait {} claimed but unreadable; releasing claim", wait.getId());
                        releaseClaim(wait.getId());
                        continue;
                    }
                    chatRuntimeService.onResumeOutcome(claimed, chatRuntimeService.resumeFromWait(claimed));
                } catch (Throwable e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // One failure must not sink the batch: the remaining waits should still be woken
                    log.error("Failed to resume pending wait {}", wait.getId(), e);
                    requeueBestEffort(wait.getId());
                    if (e instanceof Error error) {
                        throw error;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Wait wake sweep failed", e);
        } finally {
            if (locked) {
                try {
                    if (redissonClient.getLock(WAKE_LOCK_KEY).isHeldByCurrentThread()) {
                        redissonClient.getLock(WAKE_LOCK_KEY).unlock();
                    }
                } catch (Exception e) {
                    log.warn("Failed to release wait wake lock", e);
                }
            }
        }
    }

    /** Restore the claim: restore attempts via the record first, and if unreadable restore atomically by id (no read-back needed). */
    private void requeueBestEffort(Long waitId) {
        try {
            PendingWaitVO w = pendingWaitSpi.getById(waitId);
            if (w != null) {
                chatRuntimeService.onResumeOutcome(w, ChatRuntimeService.ResumeOutcome.ERROR);
                return;
            }
        } catch (Exception e) {
            log.error("Failed to requeue pending wait {}", waitId, e);
        }
        releaseClaim(waitId);
    }

    private void releaseClaim(Long waitId) {
        try {
            if (!pendingWaitSpi.releaseClaim(waitId)) {
                log.warn("Wait {} could not be released (already terminal or retries exhausted)", waitId);
            }
        } catch (Exception e) {
            log.error("Failed to release claim for wait {}", waitId, e);
        }
    }
}
