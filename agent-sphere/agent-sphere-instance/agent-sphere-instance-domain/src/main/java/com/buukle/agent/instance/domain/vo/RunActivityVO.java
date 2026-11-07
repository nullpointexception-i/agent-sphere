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
    private String reasoning;
    private String replyContent;
    private Integer httpStatus;
    private Integer durationMs;
    private String llmErrorMessage;
    private Boolean success;
    /** Normalized usage of this call (only populated on llm_interaction rows). */
    private String usage;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;

    private Long stepId;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String argumentsJson;
    private String artifact;
    private String toolStatus;
    private String toolErrorMessage;
}
