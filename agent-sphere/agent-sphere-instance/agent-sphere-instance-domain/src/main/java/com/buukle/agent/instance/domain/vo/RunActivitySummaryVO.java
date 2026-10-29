package com.buukle.agent.instance.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/** 活动列表轻量投影：不包含 request/response/reasoning/args/artifact 等大字段。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunActivitySummaryVO implements Serializable {
    public static final String ACTIVITY_TYPE_LLM = "llm_interaction";
    public static final String ACTIVITY_TYPE_TOOL = "tool_call";

    private Long id;
    private String activityType;
    private String createdAt;
    private Long sessionId;

    private String interactionType;
    private String modelName;
    private Integer httpStatus;
    private Integer durationMs;
    private Boolean success;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;

    private Long stepId;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String toolStatus;
}
