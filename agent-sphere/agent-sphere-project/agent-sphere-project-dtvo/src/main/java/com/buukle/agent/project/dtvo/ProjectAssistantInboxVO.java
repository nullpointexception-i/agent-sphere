package com.buukle.agent.project.dtvo;

import lombok.Data;

/** One targeted question waiting for the current user. */
@Data
public class ProjectAssistantInboxVO {
    private String clarificationId;
    private Long runId;
    private Long sessionId;
    private Long projectId;
    private String projectName;
    private Long requirementId;
    private String requirementKey;
    private String title;
    private String type;
    private Boolean multiple;
    private String options;
    private String description;
    private Long requesterUserId;
    private String requesterName;
    private String expiresAt;
    private String createdAt;
}
