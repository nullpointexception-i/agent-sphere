package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Historical session cleanup report (shared structure for the scheduled task and the manual endpoint).
 *
 * <p>{@code tableStats} maps table name to affected row count: during a dry-run these are the rows
 * that "would be deleted"; on a real execution they are the rows "already deleted". The order
 * follows the deletion order so it can be verified by hand.
 */
@Data
public class SessionCleanupReportVO implements Serializable {

    /** Whether this run is a rehearsal (true=count only, no deletion) */
    private boolean dryRun;

    /** Cleanup switch (session.cleanup-enabled); false means this run was skipped outright */
    private boolean enabled;

    /** True when another replica or another manual execution holds the lock; nothing was done in that case */
    private boolean skippedByLock;

    private String skipReason;

    /** Effective retention days (already fell back to the default when the configuration was invalid) */
    private int retentionDays;

    private int fileRetentionDays;

    /** Expiry cutoff: a session is expired when both created_at and updated_at are earlier than this */
    private LocalDateTime cutoff;

    private LocalDateTime fileCutoff;

    /** Number of sessions deleted */
    private int sessionCount;

    /**
     * Progress denominator: total expired sessions counted before the run.
     * The synchronous execution path (unit tests/internal calls) also carries it; the asynchronous
     * path stores it on the execution record for the frontend to poll.
     */
    private int totalSessions;

    /** Sessions blocked by the active guard because they still have a PENDING/RUNNING run or task */
    private long skippedActiveSessions;

    /** Affected row count per table */
    private Map<String, Long> tableStats = new LinkedHashMap<>();

    /** Number of batches processed (bounded by max-batches; reaching the cap ends the run early) */
    private int batches;

    /** Whether the run ended early because the per-round batch cap was reached (false=all expired data was cleaned) */
    private boolean truncated;

    private long elapsedMs;

    /** After a hard delete, space only becomes reusable inside the database and the data files do not shrink; this field is the handling advice for operators */
    private String vacuumHint;
}
