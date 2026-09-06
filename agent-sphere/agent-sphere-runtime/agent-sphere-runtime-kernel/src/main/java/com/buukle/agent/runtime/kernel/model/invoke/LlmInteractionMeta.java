package com.buukle.agent.runtime.kernel.model.invoke;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LlmInteractionMeta {
    private Long runId;
    private Long sessionId;
    private LlmInteractionType interactionType;
    /** 归属的子 Agent 运行（NULL=主 Agent）。 */
    private Long subAgentRunId;

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
