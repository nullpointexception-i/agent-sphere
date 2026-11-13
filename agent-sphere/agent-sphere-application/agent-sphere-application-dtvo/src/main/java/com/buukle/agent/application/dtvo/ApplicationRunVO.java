package com.buukle.agent.application.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApplicationRunVO {
    private Long id;
    private Long applicationId;
    private Long sceneId;
    private String triggerType;
    private String triggerKey;
    private String idempotencyKey;
    private Long requesterUserId;
    private Long workItemId;
    private Long threadId;
    private String runMode;
    private String outputLocale;
    private String status;
    private Integer attempt;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
