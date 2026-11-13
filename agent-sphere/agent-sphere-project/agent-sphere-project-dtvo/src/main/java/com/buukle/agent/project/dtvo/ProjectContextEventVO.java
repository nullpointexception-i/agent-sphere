package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectContextEventVO {
    private Long id;
    private Long projectId;
    private Long requirementId;
    private String eventType;
    private String summary;
    private Long sourceSessionId;
    private Long canonicalSessionId;
    private Long actorUserId;
    private LocalDateTime createdAt;
}
