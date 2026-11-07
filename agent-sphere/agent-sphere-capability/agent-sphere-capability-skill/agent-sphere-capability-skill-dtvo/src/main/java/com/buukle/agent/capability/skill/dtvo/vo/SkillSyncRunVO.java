package com.buukle.agent.capability.skill.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** One execution record of a skill auto-sync round (one {@code capability_skill_sync_run} row). */
@Data
public class SkillSyncRunVO implements Serializable {

    private Long id;
    /** SCHEDULED / MANUAL */
    private String triggerType;
    /** RUNNING / SUCCESS / SKIPPED / FAILED */
    private String status;
    /** Total number of copies with auto-update enabled found in this round */
    private Integer scannedCount;
    /** Number actually processed */
    private Integer handled;
    private Integer updated;
    private Integer skipped;
    private Integer batchSize;
    /** Skip details: one entry per copy, including the reason */
    private List<SkippedCopy> detail;
    private String skipReason;
    private String errorMessage;
    private Long elapsedMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** Suspected interruption: a crashed process left the record stuck in RUNNING */
    private Boolean stale;
    private String createdBy;

    /** Why a single copy was skipped. */
    @Data
    public static class SkippedCopy implements Serializable {
        private Long copyId;
        private Long originId;
        /** A non-UPDATED value from SkillSyncOutcome */
        private String reason;
        private Integer originVersion;
        private Integer sourceVersion;
    }
}
