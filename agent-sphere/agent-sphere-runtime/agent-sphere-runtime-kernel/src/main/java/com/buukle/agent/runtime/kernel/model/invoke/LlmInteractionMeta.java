package com.buukle.agent.runtime.kernel.model.invoke;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LlmInteractionMeta {
    private Long runId;
    private Long sessionId;
    private LlmInteractionType interactionType;
    /** Super-Agent ownership for cost attribution. */
    private Long superAgentId;
    /** CONSOLE / WIDGET / TASK / GROUP_ROUTER / ROUTER / WORKER / WATCHDOG. */
    private String sessionType;
    /** Super-Agent thread id for cost attribution. */
    private Long threadId;
    /** Owning sub-agent run (NULL = main agent). */
    private Long subAgentRunId;
    /** Upstream HTTP status code (carried by error events; 200 for a normal finish; null when there was no HTTP interaction). */
    private Integer httpStatus;
    /** Model route id actually used by this request (used to isolate token calibration per route). */
    private Long modelRouteId;
    /** Character-estimated prompt tokens before sending (used for actual/estimated calibration). */
    private Integer estimatedPromptTokens;
    /** Main-agent Q&A loop number (= run.loop_count); null for non-main-agent calls. */
    private Integer turnNo;
    /** Route attempt number within the same loop: 0 = primary route, 1..N = fallback. */
    private Integer attemptNo;
    /** This call's step on the LLM backoff ladder (1..N); null for non-retry loops. */
    private Integer retryAttempt;
    /** Id of the interaction row pre-created before the call; when null, the legacy "insert after the call" path is used. */
    private Long recordId;

    public LlmInteractionMeta(Long runId, Long sessionId, LlmInteractionType interactionType, Long subAgentRunId) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.interactionType = interactionType;
        this.subAgentRunId = subAgentRunId;
    }

    public LlmInteractionMeta(Long runId, Long sessionId, LlmInteractionType interactionType) {
        this(runId, sessionId, interactionType, null);
    }

    public LlmInteractionMeta() {
    }
}
