package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectInsightVO {
    private Long id;
    private Long projectId;
    private Long requirementId;
    private String requirementKey;
    private String insightType;
    private String severity;
    private String title;
    private String content;
    private String source;
    private Long sourceRunId;
    private String createdBy;
    private LocalDateTime createdAt;
}
