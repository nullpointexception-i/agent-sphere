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
    /** 上游 HTTP 状态码（错误事件携带；正常结束记 200；无 HTTP 交互时为 null）。 */
    private Integer httpStatus;
    /** 本次请求实际使用的模型路由 id（用于 token 校准按路由隔离）。 */
    private Long modelRouteId;
    /** 请求发送前的字符估算 prompt token（用于 actual/estimated 校准）。 */
    private Integer estimatedPromptTokens;

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
