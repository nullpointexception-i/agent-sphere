package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

/** Baseline pipeline status for the project assistant panel. */
@Data
public class ProjectBaselineVO {
    private Long projectId;
    private boolean autoAnalyze;
    private long pendingEvents;
    private long runningBatches;
    private String lastBatchStatus;
    private LocalDateTime lastBatchAt;
    private String lastBatchError;
}
