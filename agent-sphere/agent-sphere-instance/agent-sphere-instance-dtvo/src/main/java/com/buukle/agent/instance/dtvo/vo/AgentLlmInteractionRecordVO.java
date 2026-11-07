package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentLlmInteractionRecordVO implements Serializable {
    private Long id;
    private Long runId;
    private Long sessionId;
    private Long superAgentId;
    private String sessionType;
    private Long threadId;
    /** Main Agent question/answer turn number (= run.loop_count). */
    private Integer turnNo;
    /** Route attempt number within the same turn: 0=primary route, 1..N=fallback. */
    private Integer attemptNo;
    /** Call number under the same (run, turn, attempt) (one row per retry). */
    private Integer retryNo;
    /** Position of this call on the LLM backoff ladder (1..N); NULL for non-retry turns. */
    private Integer retryAttempt;
    /** RUNNING / COMPLETED / FAILED。 */
    private String status;
    private String interactionType;
    private String modelName;
    private String requestBody;
    private String responseBody;
    private Integer httpStatus;
    private String finishReason;
    private Integer durationMs;
    private String errorMessage;
    private Boolean success;
    private String reasoning;
    private String replyContent;
    private Long subAgentRunId;
    /** Normalized usage JSON (TokenUsage, provider-agnostic). */
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
    /** Type and summary of the most recent transport-layer failure. */
    private String streamError;
    private String createdBy;
    private String createdAt;
    /** Epoch milliseconds of createdAt (millisecond precision; only for historical rebuild pairing, not for display). */
    private Long createdAtMillis;
}
