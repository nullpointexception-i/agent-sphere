package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <strong>Database row shape</strong> of a session cleanup execution record; only used by
 * {@code SessionCleanupRunMapper}.
 *
 * <p>The only difference from the API-layer {@code SessionCleanupRunVO} (instance-dtvo) is that
 * {@code tableStats} is a string: the column type is jsonb, and MyBatis auto-mapping cannot turn
 * JSON text into a {@code Map} out of nowhere ({@code JsonbTypeHandler} lives in infrastructure,
 * and instance must not depend on it in reverse). So this reads
 * {@code CAST(table_stats AS text)} and the caller converts it to a Map with JsonUtils before
 * populating the VO.
 */
@Data
public class SessionCleanupRunRowVO implements Serializable {

    private Long id;
    private String triggerType;
    private boolean dryRun;
    private String status;
    private Integer retentionDays;
    private Integer fileRetentionDays;
    private LocalDateTime cutoff;
    private LocalDateTime fileCutoff;
    private Integer sessionCount;
    /** Progress denominator: total expired sessions counted before the run (column added in V78) */
    private Integer totalSessions;
    private Long totalRows;
    private Long skippedActiveSessions;
    private Integer batches;
    private Boolean truncated;
    private Long elapsedMs;
    /** jsonb in text form */
    private String tableStats;
    private String skipReason;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** Suspected interruption: no terminal state yet and the start time is past the threshold */
    private Boolean stale;
    private String createdBy;
    /** Hint written by the backend for operators (currently carries the VACUUM suggestion; the table is machine-written and has no manual-remark scenario) */
    private String remark;
}