package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_llm_interaction_record")
public class AgentLlmInteractionRecord {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long sessionId;
    private Long superAgentId;
    private String sessionType;
    private Long threadId;
    /** Main Agent question/answer turn number (= run.loop_count); NULL for other calls. */
    private Integer turnNo;
    /** Route attempt number within the same turn: 0=primary route, 1..N=fallback. */
    private Integer attemptNo;
    /** Call number under the same (run, turn, attempt): every retry/new turn gets its own row, so a successful result is never overwritten. */
    private Integer retryNo;
    /** Position of this call on the LLM backoff ladder (1..N, V117); NULL for non-retry turns. */
    private Integer retryAttempt;
    /** RUNNING / COMPLETED / FAILED. */
    private String status;
    private String interactionType;
    private String modelName;
    private String requestBody;
    private String responseBody;
    private Integer httpStatus;
    private Integer durationMs;
    /** Provider's real finish_reason (stop/length/content_filter/tool_calls); truncation stays auditable. */
    private String finishReason;
    private String errorMessage;
    private Boolean success;
    /** Full thinking (reasoning_content) of this LLM call, stored separately from response_body (reply+merged). */
    private String reasoning;
    /** Reply body (pure reply text split out from reasoning; falls back to response_body when absent). */
    private String replyContent;
    /** Owning sub-agent run (NULL=main Agent). */
    private Long subAgentRunId;
    /** Normalized usage JSON (TokenUsage, a provider-agnostic mirror; redundant scalar columns feed aggregation). */
    private String usage;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
    /** Actual Provider transport-layer attempts (including the first one). */
    private Integer streamAttempts;
    /** Provider transport-layer retry count (attempts-1). */
    private Integer streamRetryCount;
    /** Type and summary of the most recent transport-layer failure (may also be non-null on a successful record). */
    private String streamError;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
