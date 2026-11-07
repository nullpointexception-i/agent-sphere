package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;

import java.time.Duration;
import java.util.List;

public interface PendingWaitSpi {

    /**
     * Write a suspend record and trigger the in-memory timer (the implementation publishes a local event internally).
     *
     * <p>The caller (ToolExecutor intercepting the wait tool) uses the return value for logging only:
     * whether the run is actually suspended is expressed by the tool result {@code {"status":"waiting"}}.
     *
     * @param waitMs actual wait in milliseconds; the implementation must clamp it to the [MIN, MAX] range
     * @return id of the new record
     */
    Long createPending(Long sessionId, Long runId, String callId, int waitMs);

    /**
     * Write a suspend-and-retry record for a transient LLM failure (kind=LLM_RETRY).
     *
     * <p>It shares the scheduler with {@link #createPending} (in-memory timer + cron compensation) but
     * has different wake semantics: a retry does not need to backfill the wait tool result and does
     * not append a synthetic "your wait is over" user message.
     *
     * @param attempt     which failure this is (starting at 1)
     * @param reason      error category code (provider_busy / connect_timeout / rate_limited / unknown_transient)
     * @param errorStatus upstream HTTP status code, may be null
     * @param lastError   raw error text (truncated before persisting)
     * @return id of the new record
     */
    Long createRetry(Long sessionId, Long runId, int attempt, java.time.LocalDateTime wakeAt,
                     String reason, Integer errorStatus, String lastError);

    /**
     * CAS claim: set the record to RESUMED and return {@code true} only when it is still PENDING.
     *
     * <p>Both the in-memory timer and the cron compensation call it — they may hit the same record at
     * the same time, and only the party that claims successfully may wake the run; otherwise the same
     * run would be resumed twice.
     */
    boolean claimForResume(Long waitId);

    /** Compensation sweep: fetch due records that are still PENDING (ascending by wake_at). */
    List<PendingWaitVO> listDue(Duration overdueBy, int limit);

    /** Fetch one wait record by id (the detail is read back after the CAS during wake-up). */
    PendingWaitVO getById(Long waitId);

    /**
     * Cancel all still-PENDING suspend records under a run (set them to CANCELLED).
     *
     * <p>Called when the run is stopped: the loop has already exited and the cancellation flag has no
     * consumer; if the suspend records are not moved out of PENDING, the waker
     * ({@link #listDue}/{@link #claimForResume} only accept PENDING) will still try to resume the run
     * when the time arrives.
     *
     * @return number of records cancelled
     */
    int cancelPendingByRunId(Long runId);

    /**
     * Put the record back to PENDING after a failed resume so it can be retried: only applies when the
     * record is RESUMED; attempts +1 and last_error is recorded.
     *
     * <p>Wake-up is "first CAS to RESUMED, then actually resume"; if resuming throws and the record
     * stays RESUMED, {@link #listDue}/{@link #claimForResume} only accept PENDING → cron never retries
     * → the run stays stuck in WAITING forever. A failure must therefore put it back to PENDING (this method).
     *
     * @return attempts after putting it back; -1 when the put-back failed (the record is not RESUMED)
     */
    int requeueForRetry(Long waitId, String error);

    /**
     * Fallback for "the record cannot be read back" after claim: RESUMED → PENDING, attempts + 1.
     *
     * <p>The wake-up path must first {@link #claimForResume} and then read the detail back before it
     * can resume; if the read-back fails (it once inherited another tenant on a background thread and
     * {@code DataPermissionInterceptor} appended a {@code created_by} filter, returning null), the
     * record stays RESUMED — and {@link #listDue}/{@link #claimForResume} only accept PENDING, so cron
     * never picks it up and the run can only wait for the reconciliation task.
     *
     * <p><b>Deliberately no read-back</b>: a failed read-back is exactly the case to handle, and only a
     * single atomic UPDATE can put it back on the queue.
     *
     * @return true means it has been put back to PENDING (cron can retry); false means the record is no longer RESUMED or the retry cap is reached
     */
    boolean releaseClaim(Long waitId);

    /** Move a wait record to a terminal state (CANCELLED / FAILED), recording the reason. */
    void markTerminal(Long waitId, String status, String error);

    /** Fetch still-PENDING suspend records under a run (for reconciliation; at most one). */
    List<PendingWaitVO> listPendingByRunId(Long runId);
}