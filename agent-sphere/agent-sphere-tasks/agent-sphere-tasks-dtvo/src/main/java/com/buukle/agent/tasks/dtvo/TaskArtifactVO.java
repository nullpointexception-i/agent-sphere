package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/** 任务产物（task artifact）视图：列表/详情共用。 */
@Data
public class TaskArtifactVO implements Serializable {
    private Long id;
    private Long taskId;
    /** 任务目标（联表带出，便于列表可读）。 */
    private String taskGoal;
    private String artifactType;
    private String content;
    private String schemaRef;
    private Long runId;
    private String status;
    private String remark;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
