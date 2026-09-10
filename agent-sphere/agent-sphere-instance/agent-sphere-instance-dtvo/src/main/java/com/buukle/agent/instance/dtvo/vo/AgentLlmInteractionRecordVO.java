package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentLlmInteractionRecordVO implements Serializable {
    private Long id;
    private Long runId;
    private Long sessionId;
    private String interactionType;
    private String modelName;
    private String requestBody;
    private String responseBody;
    private Integer httpStatus;
    private Integer durationMs;
    private String errorMessage;
    private Boolean success;
    private String reasoning;
    private String replyContent;
    private Long subAgentRunId;
    /** 归一化用量 JSON（TokenUsage，供应商无关）。 */
    private String usage;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
    private String createdBy;
    private String createdAt;
}
