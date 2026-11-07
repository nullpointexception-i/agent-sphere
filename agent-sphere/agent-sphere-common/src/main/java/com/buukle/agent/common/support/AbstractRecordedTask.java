package com.buukle.agent.common.support;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;

/**
 * Shared skeleton for long-running background tasks: acquire lock → execute → record failure →
 * release lock.
 *
 * <p>Extracted because session cleanup ({@code SessionCleanupTask}) and Skill Hub auto-sync
 * ({@code SkillSyncRunner}) are structurally identical: both may run concurrently on multiple
 * replicas, both take minutes, and both need execution records to answer "did it run, and how did
 * it go". Copying this lock and exception semantics would eventually drift in the two places.
 *
 * <p><b>The record is written before the lock is taken</b>: "lock was busy" and "kill switch off"
 * did nothing, but must still leave a trace, otherwise nobody can explain afterwards "why this
 * round did not run". That is why inserting the record is done by the caller before acquiring the
 * lock.
 *
 * <p>The base class <b>assumes no table structure</b>; subclasses only implement record read/write
 * and business logic, but the convention stays consistent:
 * {@code trigger_type / status / started_at / finished_at / skip_reason / error_message}. That way
 * the frontend polling panel can be written against the same shape.
 *
 * @param <T> result type of one execution, defined by the subclass (a report for session cleanup,
 *            statistics for sync)
 * @param <C> context of a single execution (for session cleanup it is {@code Boolean dryRun}).
 *            <b>Must be passed as a parameter, never stored in a field</b>: the body usually runs on
 *            a virtual thread, and a field (even a ThreadLocal) cannot be read from another thread
 *            and silently degrades to its default — for a switch like "dry run vs actually delete",
 *            the degraded direction is <b>actually delete</b>, and the consequence is irreversible.
 */
@Slf4j
public abstract class AbstractRecordedTask<T, C> {

    /** Scheduled tasks have no request context, so they are recorded as system (matching the fallback in {@code AuditMetaObjectHandler}). */
    protected static final String SYSTEM_OPERATOR = "system";

    /** Standard wording for multi-replica mutual exclusion. */
    protected static final String SKIP_LOCK_REASON = "Another replica or execution is already running; skipped this round";

    /** Still RUNNING after this long is marked stale: the process died, so do not let ops misread it as "still running". */
    public static final long STALE_THRESHOLD_MINUTES = 30L;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    protected final RedissonClient redissonClient;

    protected AbstractRecordedTask(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Runs while holding the lock: when the lock cannot be acquired it goes through
     * {@link #onLockBusy}; an exception during execution is first recorded as FAILED and then
     * rethrown.
     *
     * <p>The subclass owns the business logic and the SUCCESS/SKIPPED writes inside {@link #doRun} —
     * it is the one holding the context needed to decide.
     */
    protected final T runWithLock(C context, Long recordId, String operator, long startedAtMillis) {
        long elapsedBeforeLock = System.currentTimeMillis() - startedAtMillis;
        RLock lock = redissonClient.getLock(lockKey());
        if (!lock.tryLock()) {
            return onLockBusy(context, recordId, operator, elapsedBeforeLock);
        }
        try {
            return doRun(context, recordId, operator, startedAtMillis);
        } catch (Exception e) {
            // Nobody receives the exception on the async path, so the full stack trace must be logged here
            markFailed(recordId, e, System.currentTimeMillis() - startedAtMillis, operator);
            log.error("{} failed: recordId={}", taskName(), recordId, e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** Default behavior when the lock cannot be acquired: record SKIPPED and return null. Subclasses that need to return a business object may override. */
    protected T onLockBusy(C context, Long recordId, String operator, long elapsedMillis) {
        markSkipped(recordId, SKIP_LOCK_REASON, elapsedMillis, operator);
        return null;
    }

    // ==================== subclasses must implement ====================

    /** Redisson lock key; recommended under the {@code scheduler:<name>} namespace. */
    protected abstract String lockKey();

    /** Inserts the RUNNING record and returns the primary key. Must be called before taking the lock. */
    protected abstract Long insertRunning(C context, String operator);

    /** The actual business logic (already holding the lock). The subclass records SUCCESS / SKIPPED at the appropriate time. */
    protected abstract T doRun(C context, Long recordId, String operator, long startedAtMillis);

    /** Records SKIPPED: nothing was done, and why. */
    protected abstract void markSkipped(Long recordId, String reason, long elapsedMillis, String operator);

    /** Records FAILED: an exception summary; the base class then rethrows the exception. */
    protected abstract void markFailed(Long recordId, Exception e, long elapsedMillis, String operator);

    /** Task name, used only for logging. */
    protected abstract String taskName();

    // ==================== shared helpers ====================

    /**
     * The operator recorded on the record. Priority matches
     * {@code AuditMetaObjectHandler.currentUser()} (tenant → logged-in user → system). It is inlined
     * here because that Handler lives in infrastructure, which neither common nor the business
     * modules can reach.
     */
    protected String currentOperator() {
        String tenant = TenantUtil.get();
        if (tenant != null && !tenant.isBlank()) {
            return tenant;
        }
        String auth = AuthContext.getUsername();
        if (auth != null && !auth.isBlank()) {
            return auth;
        }
        return SYSTEM_OPERATOR;
    }

    /** Cutoff for the "suspected interrupted" check. */
    protected LocalDateTime staleBefore() {
        return LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
    }

    /** Truncates the error message so an over-long description does not bloat the record table. */
    protected String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}