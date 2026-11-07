package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Sub-agent run timeline item: an LLM interaction (with reasoning/reply) or a tool_call,
 * rendered chronologically by the frontend after sorting by created_at.
 */
@Data
public class SubAgentTimelineItemVO implements Serializable {
    private String activityType;   // llm_interaction | tool_call
    private String createdAt;
    private Long interactionId;
    private String interactionType;
    private String modelName;
    private String reasoning;      // Full thinking text of this LLM call
    private String reply;          // Reply body of this call
    private Boolean success;
    private Long stepId;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String argumentsJson;
    private String artifact;
    private String toolStatus;
    private String toolErrorMessage;
    /** Browser screenshot references in the tool result ([{fileKey, contentType}]), echoed by the container card. */
    private List<RunAttachment> images;
}