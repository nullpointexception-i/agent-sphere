package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * A session cleanup execution record (one row of {@code agent_session_cleanup_run}).
 *
 * <p>Rehearsals ({@code dryRun=true}) are recorded too: cleanup is irreversible, and
 * "what was rehearsed at the time, how many rows were seen" is a key clue for later review, which
 * this field is enough to distinguish.
 */
@Data
public class SessionCleanupRunVO implements Serializable {

    private Long id;

    /** SCHEDULED / MANUAL */
    private String triggerType;

    /** true=count only, nothing deleted; false=real hard delete */
    private boolean dryRun;

    /** RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    private Integer retentionDays;

    private Integer fileRetentionDays;

    private LocalDateTime cutoff;

    private LocalDateTime fileCutoff;

    private Integer sessionCount;

    /** Progress denominator: total expired sessions counted before the run; the frontend computes the percentage from it */
    private Integer totalSessions;

    private Long totalRows;

    private Long skippedActiveSessions;

    private Integer batches;

    private Boolean truncated;

    private Long elapsedMs;

    /** Affected row count per table */
    private Map<String, Long> tableStats;

    private String skipReason;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /**
     * Suspected interruption: a crashed process leaves the record stuck in RUNNING forever.
     * Determined by SQL as {@code finished_at IS NULL AND started_at < now() - 30min}, so operators
     * do not misread a zombie record as "cleanup in progress".
     */
    private Boolean stale;

    private String createdBy;

    /** Operator hint (currently the post-hard-delete VACUUM advice); written by the backend and displayed verbatim by the frontend */
    private String remark;
}