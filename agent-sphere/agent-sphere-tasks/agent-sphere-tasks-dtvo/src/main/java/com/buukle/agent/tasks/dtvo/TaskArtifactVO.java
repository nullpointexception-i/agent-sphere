package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/** Task artifact view: shared by list and detail. */
@Data
public class TaskArtifactVO implements Serializable {
    private Long id;
    private Long taskId;
    /** Task goal (joined in so the list is readable). */
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
