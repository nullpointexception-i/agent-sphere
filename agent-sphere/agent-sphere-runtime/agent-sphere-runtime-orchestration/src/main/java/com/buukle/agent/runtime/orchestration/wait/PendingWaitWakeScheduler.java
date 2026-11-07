package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.spi.PendingWaitCreatedEvent;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Primary wake path for wait: one in-memory timer per waiting record, firing recovery when due.
 *
 * <p>Runs follow "single writer (this executing replica)" semantics (see the RuntimeEventListener comments),
 * so scheduling the timer on the replica that created the wait is sufficient; no cross-replica coordination is needed.
 * A replica restart loses all in-memory timers — the {@code WaitWakeTask} cron covers that as a fallback.
 *
 * <p>Both paths first do a {@code claimForResume} CAS, and only the winner actually resumes,
 * so a timer and the cron hitting the same record at once cannot resume a run twice.
 *
 * <p><b>The wake thread must have no tenant.</b> It is a lazily created long-lived single thread and, through {@code InheritableThreadLocal}, inherits
 * the {@code TenantUtil} of "the thread that first called schedule" (the console session's username) —
 * {@code DataPermissionInterceptor} then appends {@code created_by=<that user>} to every SELECT,
 * so {@code getById} cannot read other users' waiting records and the resume is silently skipped. Task-type runs do not set
 * {@code TenantUtil}, so in the past the thread was usually clean and the problem stayed masked.
 */
@Slf4j
@Component
public class PendingWaitWakeScheduler {

    private final PendingWaitSpi pendingWaitSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    /**
     * Daemon single-thread pool: wait recovery itself is fast (persist + emit event + submit async execution),
     * the real latency is inside asyncHandleUserMessage and should not occupy this thread.
     */
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pending-wait-wake");
                t.setDaemon(true);
                return t;
            });

    public PendingWaitWakeScheduler(PendingWaitSpi pendingWaitSpi, ChatRuntimeService chatRuntimeService) {
        this.pendingWaitSpi = pendingWaitSpi;
        this.chatRuntimeService = chatRuntimeService;
    }

    @EventListener
    public void onPendingWaitCreated(PendingWaitCreatedEvent event) {
        long delayMs = Math.max(0, Duration.between(Instant.now(), event.wakeAt()).toMillis());
        log.info("Scheduling wait wake: waitId={} runId={} delayMs={}",
                event.waitId(), event.runId(), delayMs);
        schedule(event.waitId(), delayMs);
    }

    void schedule(Long waitId, long delayMs) {
        cancel(waitId);
        ScheduledFuture<?> future = executor.schedule(
                () -> wake(waitId), delayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = scheduled.put(waitId, future);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    private void wake(Long waitId) {
        clearInheritedContext();
        try {
            if (!pendingWaitSpi.claimForResume(waitId)) {
                // Already claimed by the cron, or resumed by another path: exit quietly
                log.debug("Wait {} already claimed; skipping in-memory wake", waitId);
                return;
            }
            PendingWaitVO wait = pendingWaitSpi.getById(waitId);
            if (wait == null) {
                // Must not drop silently: the claim is already written, so returning here would leave the record at RESUMED where the cron can never pick it up.
                log.warn("Wait {} claimed but unreadable; releasing claim so cron can retry", waitId);
                releaseClaim(waitId);
                return;
            }
            chatRuntimeService.onResumeOutcome(wait, chatRuntimeService.resumeFromWait(wait));
        } catch (Throwable t) {
            // catch Throwable: an Error (OOM etc.) also leaves the record at RESUMED, so restore it best-effort before rethrowing.
            log.error("Failed to wake pending wait {}", waitId, t);
            requeueBestEffort(waitId);
            if (t instanceof Error error) {
                throw error;
            }
        } finally {
            scheduled.remove(waitId);
        }
    }

    /**
     * Clear tenant/identity inherited from the creating thread. A background thread should never carry request context;
     * otherwise {@code DataPermissionInterceptor} filters out this record using someone else's {@code created_by}.
     */
    static void clearInheritedContext() {
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.clear();
    }

    /** Any exception after claim must restore PENDING (best-effort), otherwise the record stays RESUMED and the cron never retries. */
    private void requeueBestEffort(Long waitId) {
        try {
            PendingWaitVO w = pendingWaitSpi.getById(waitId);
            if (w != null) {
                chatRuntimeService.onResumeOutcome(w, ChatRuntimeService.ResumeOutcome.ERROR);
                return;
            }
        } catch (Exception e) {
            log.error("Failed to requeue wait {} after wake error", waitId, e);
        }
        // The read-back failed too: fall back to an atomic restore by id, otherwise nothing will ever touch this record again.
        releaseClaim(waitId);
    }

    /** Put a record that was claimed successfully but cannot be read back to PENDING (no read-back). */
    private void releaseClaim(Long waitId) {
        try {
            if (!pendingWaitSpi.releaseClaim(waitId)) {
                log.warn("Wait {} could not be released (already terminal or retries exhausted)", waitId);
            }
        } catch (Exception e) {
            log.error("Failed to release claim for wait {}", waitId, e);
        }
    }

    private void cancel(Long waitId) {
        ScheduledFuture<?> prev = scheduled.remove(waitId);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** Exposed for compensation tasks: trigger once manually (for immediate recovery after in-process timers are lost). */
    public void triggerNow(Long waitId) {
        cancel(waitId);
        schedule(waitId, 0);
    }
}