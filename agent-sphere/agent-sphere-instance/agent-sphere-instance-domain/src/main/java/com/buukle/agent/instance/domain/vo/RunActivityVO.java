package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class RunActivityVO implements Serializable {
    private Long id;
    private String activityType;
    private String createdAt;
    private Long sessionId;

    private String interactionType;
    private String modelName;
    private String requestBody;
    private String responseBody;
    private Integer httpStatus;
    private Integer durationMs;
    private String llmErrorMessage;
    private Boolean success;

    private Long stepId;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String argumentsJson;
    private String artifact;
    private String toolStatus;
    private String toolErrorMessage;
}
