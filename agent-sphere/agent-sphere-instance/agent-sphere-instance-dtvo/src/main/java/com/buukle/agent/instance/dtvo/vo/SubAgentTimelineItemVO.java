package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 子 Agent 运行时间线条目：LLM interaction（含 reasoning/reply）或 tool_call，
 * 按 created_at 排序后由前端时序渲染。
 */
@Data
public class SubAgentTimelineItemVO implements Serializable {
    private String activityType;   // llm_interaction | tool_call
    private String createdAt;
    private Long interactionId;
    private String interactionType;
    private String modelName;
    private String reasoning;      // 该次 LLM 调用的 thinking 全文
    private String reply;          // 该次调用的回复正文
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