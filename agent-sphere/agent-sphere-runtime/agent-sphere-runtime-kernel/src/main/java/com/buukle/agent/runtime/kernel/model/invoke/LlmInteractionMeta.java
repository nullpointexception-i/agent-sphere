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
    /** 主 Agent 问答轮次序号（= run.loop_count）；非主 Agent 调用为 null。 */
    private Integer turnNo;
    /** 同一轮内的路由尝试序号：0=主路由，1..N=fallback。 */
    private Integer attemptNo;
    /** 调用前预建的 interaction 记录 id；为 null 时走「调用结束后插入」的旧路径。 */
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
