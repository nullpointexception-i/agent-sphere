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
    private String createdBy;
    private String createdAt;
}
