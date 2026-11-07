package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;

/** Usage aggregation for a single session (sum of LLM calls across all runs, for session-level display). */
@Data
public class SessionUsageVO implements Serializable {
    private Long sessionId;
    /** Number of runs covered. */
    private Long runCount;
    /** Total number of LLM calls in the session. */
    private Long interactionCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long cacheHitTokens;
    private Long cacheMissTokens;
    /** Prompt tokens of the latest main Agent CHAT_REPLY (current context usage); null when there is no record. */
    private Long latestPromptTokens;
    /** Cache hit rate (0-100; null when there is no usage). */
    private Double cacheHitRate;
}