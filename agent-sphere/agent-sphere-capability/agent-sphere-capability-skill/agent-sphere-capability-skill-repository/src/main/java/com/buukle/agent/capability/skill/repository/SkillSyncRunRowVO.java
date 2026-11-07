package com.buukle.agent.capability.skill.repository;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Database row shape of {@code capability_skill_sync_run} (jsonb read as text).
 *
 * <p>The only difference from the API-layer {@code SkillSyncRunVO} is the type of {@code detail}:
 * MyBatis auto-mapping will not turn JSON text into a List on its own, so the caller parses it
 * before assembling the VO.
 */
@Data
public class SkillSyncRunRowVO implements Serializable {

    private Long id;
    private String triggerType;
    private String status;
    private Integer scannedCount;
    private Integer handled;
    private Integer updated;
    private Integer skipped;
    private Integer batchSize;
    /** Skip detail JSON text */
    private String detail;
    private String skipReason;
    private String errorMessage;
    private Long elapsedMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** Suspected interruption: not terminal and the start time is past the threshold */
    private Boolean stale;
    private String createdBy;
}
