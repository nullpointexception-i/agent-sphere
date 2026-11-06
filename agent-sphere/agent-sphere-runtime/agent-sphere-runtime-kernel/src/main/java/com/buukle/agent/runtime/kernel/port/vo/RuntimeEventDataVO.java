package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Builder;

/**
 * 不可变运行时事件负载。
 *
 * <p>事件对象在 Spring 同步分发时由所有监听器共享同一引用；可变 POJO 允许某个
 * 监听器就地改写字段（历史上 {@code stampRunStatus} 把 assistantReply 改成时间戳，
 * 污染了后续 worker 报告）。改成 record 后任何改写都无法编译，派生字段只能用
 * {@code toBuilder()} 生成副本。
 *
 * <p>Bean 风格 getter 仅为兼容既有读取点。序列化用 {@code @JsonAutoDetect} 只读字段，
 * 因此 JSON 结构与改造前完全一致（不会有 getXxx 产生的重复键）。
 */
@Builder(toBuilder = true)
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record RuntimeEventDataVO(
        Long sessionId,
        Long runId,
        Long taskId,
        Long stepId,
        String type,
        String nodeName,
        String toolName,
        String displayNameCn,
        String displayNameEn,
        String argumentsJson,
        String prompt,
        String approvedBy,
        String comment,
        String response,
        String errorMessage,
        Boolean passed,
        String status,
        String assistantReply,
        /** 状态行（run_status / compaction）展示标题；与 assistantReply（正文）语义分离。 */
        String statusTitle,
        String artifact,
        /** Whether this tool result is allowed to be compressed in live/history projection. */
        Boolean compressEnabled,
        String reasoningType,
        String reasoningSubType,
        String publishId,
        /** 子 Agent LLM 轮首帧 reasoning 标记（前端据此切新 LLM 轮，替代解析 ▶ 哨兵）。 */
        Boolean firstFrame,
        String screenshot,
        String clarificationId,
        /** Choice clarification: whether multiple options may be selected. */
        Boolean multiple,
        /** 归属的子 Agent 运行（NULL=主 Agent）。 */
        Long subAgentRunId,
        /** 产生该工具调用的 LLM 轮次（= run.loop_count / interaction.turn_no），用于恢复时精确配对。 */
        Integer turnNo,
        /** 触发该子 Agent 的父工具调用 id（同批次并行子 Agent 共享，用于前端成组展示）。 */
        String parentToolCallId,
        /** 统一 Timeline 行 seq（会话级单调）——SSE 打字机/打平渲染的目标键。 */
        Long seq,
        /** 统一 Timeline 行展示类型（user|assistant|tool|clarification|subagent|run_status|compaction|error）。 */
        String kind,
        /** 压缩记录 id（CompactionStatus 事件携带；timeline 行据此回填 ref_compact_id）。 */
        Long compactRecordId,
        /** L2 tool approval id (drawer key). */
        String approvalId,
        /** L2 tool approval calls JSON (toolCallId/toolName/displayName/action/arguments/scope). */
        String toolCallsJson,
        /** 挂起类型（V115）：WAIT / LLM_RETRY。 */
        String waitKind,
        /** 重试进度：第 k 次 / 共 N 次（V115）。 */
        Integer retryAttempt,
        Integer retryMax,
        /** 下次重试时间（epoch ms）。 */
        Long nextRetryAt,
        /** 错误分类 code（provider_busy / connect_timeout / rate_limited / unknown_transient）。 */
        String retryHint,
        /** 触发重试的上游 HTTP 状态码。 */
        Integer retryHttpStatus) {

    // ---- Bean-style read accessors kept for existing call sites (serialization ignores them). ----

    public Long getSessionId() {
        return sessionId;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getStepId() {
        return stepId;
    }

    public String getType() {
        return type;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getToolName() {
        return toolName;
    }

    public String getDisplayNameCn() {
        return displayNameCn;
    }

    public String getDisplayNameEn() {
        return displayNameEn;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getComment() {
        return comment;
    }

    public String getResponse() {
        return response;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Boolean getPassed() {
        return passed;
    }

    public String getStatus() {
        return status;
    }

    public String getAssistantReply() {
        return assistantReply;
    }

    public String getStatusTitle() {
        return statusTitle;
    }

    public String getArtifact() {
        return artifact;
    }

    public Boolean getCompressEnabled() {
        return compressEnabled;
    }

    public String getReasoningType() {
        return reasoningType;
    }

    public String getReasoningSubType() {
        return reasoningSubType;
    }

    public String getPublishId() {
        return publishId;
    }

    public Boolean getFirstFrame() {
        return firstFrame;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public String getClarificationId() {
        return clarificationId;
    }

    public Boolean getMultiple() {
        return multiple;
    }

    public Long getSubAgentRunId() {
        return subAgentRunId;
    }

    public Integer getTurnNo() {
        return turnNo;
    }

    public String getParentToolCallId() {
        return parentToolCallId;
    }

    public Long getSeq() {
        return seq;
    }

    public String getKind() {
        return kind;
    }

    public Long getCompactRecordId() {
        return compactRecordId;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public String getWaitKind() {
        return waitKind;
    }

    public Integer getRetryAttempt() {
        return retryAttempt;
    }

    public Integer getRetryMax() {
        return retryMax;
    }

    public Long getNextRetryAt() {
        return nextRetryAt;
    }

    public String getRetryHint() {
        return retryHint;
    }

    public Integer getRetryHttpStatus() {
        return retryHttpStatus;
    }
}
