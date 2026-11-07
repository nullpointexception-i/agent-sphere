package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Builder;

/**
 * Immutable runtime event payload.
 *
 * <p>Event objects are shared by all listeners during Spring's synchronous dispatch; a mutable POJO lets one
 * listener rewrite fields in place (historically {@code stampRunStatus} turned assistantReply into a timestamp,
 * polluting the later worker report). With a record, any such rewrite fails to compile, so derived fields can only be
 * produced as a copy via {@code toBuilder()}.
 *
 * <p>Bean-style getters exist only for compatibility with existing read sites. Serialization uses {@code @JsonAutoDetect} on fields only,
 * so the JSON structure is exactly the same as before (no duplicate keys produced by getXxx).
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
        /** Title shown on the status row (run_status / compaction); semantically separate from assistantReply (body). */
        String statusTitle,
        String artifact,
        /** Whether this tool result is allowed to be compressed in live/history projection. */
        Boolean compressEnabled,
        String reasoningType,
        String reasoningSubType,
        String publishId,
        /** Reasoning marker of the first frame of a sub-agent LLM turn (the frontend uses it to start a new LLM turn instead of parsing the ▶ sentinel). */
        Boolean firstFrame,
        String screenshot,
        String clarificationId,
        /** Choice clarification: whether multiple options may be selected. */
        Boolean multiple,
        /** Owning sub-agent run (NULL = main agent). */
        Long subAgentRunId,
        /** LLM turn that produced this tool call (= run.loop_count / interaction.turn_no), used for exact pairing on resume. */
        Integer turnNo,
        /** Id of the parent tool call that triggered this sub-agent (shared by parallel sub-agents in the same batch, used for grouped display in the frontend). */
        String parentToolCallId,
        /** Unified Timeline row seq (session-level monotonic) — the target key for SSE typewriter / flattened rendering. */
        Long seq,
        /** Unified Timeline row display type (user|assistant|tool|clarification|subagent|run_status|compaction|error). */
        String kind,
        /** Compaction record id (carried by CompactionStatus events; timeline rows backfill ref_compact_id from it). */
        Long compactRecordId,
        /** L2 tool approval id (drawer key). */
        String approvalId,
        /** L2 tool approval calls JSON (toolCallId/toolName/displayName/action/arguments/scope). */
        String toolCallsJson,
        /** Suspension kind (V115): WAIT / LLM_RETRY. */
        String waitKind,
        /** Retry progress: attempt k of N (V115). */
        Integer retryAttempt,
        Integer retryMax,
        /** Next retry time (epoch ms). */
        Long nextRetryAt,
        /** Error classification code (provider_busy / connect_timeout / rate_limited / unknown_transient). */
        String retryHint,
        /** Upstream HTTP status code that triggered the retry. */
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
