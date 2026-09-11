package com.buukle.agent.runtime.kernel.port.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RuntimeEventDataVO {
    private Long sessionId;
    private Long runId;
    private Long taskId;
    private Long stepId;
    private String type;
    private String nodeName;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String argumentsJson;
    private String prompt;
    private String approvedBy;
    private String comment;
    private String response;
    private String errorMessage;
    private Boolean passed;
    private String status;
    private String assistantReply;
    private String artifact;
    private String reasoningType;
    private String reasoningSubType;
    private String publishId;
    /** 子 Agent LLM 轮首帧 reasoning 标记（前端据此切新 LLM 轮，替代解析 ▶ 哨兵）。 */
    private Boolean firstFrame;
    private String screenshot;
    private String clarificationId;
    /** 归属的子 Agent 运行（NULL=主 Agent）。 */
    private Long subAgentRunId;
    /** 触发该子 Agent 的父工具调用 id（同批次并行子 Agent 共享，用于前端成组展示）。 */
    private String parentToolCallId;
    /** 统一 Timeline 行 seq（会话级单调）——SSE 打字机/打平渲染的目标键。 */
    private Long seq;
    /** 统一 Timeline 行展示类型（user|assistant|tool|clarification|subagent|run_status|error）。 */
    private String kind;
}
