package com.buukle.agent.instance.dtvo.enums;

/** Content key for parsing unified Timeline row {@code content} (avoids inlining magic strings in business logic). */
public enum TimelineContentKey {
    TEXT("text"),
    THINKING("thinking"),
    REPLY("reply"),
    DISPLAY_NAME("displayName"),
    DISPLAY_NAME_EN("displayNameEn"),
    STATUS("status"),
    ARGS("args"),
    ARTIFACT("artifact"),
    CLARIFICATION_ID("clarificationId"),
    APPROVAL_ID("approvalId"),
    DECISIONS("decisions"),
    TOOL_CALLS("toolCalls"),
    TITLE("title"),
    TYPE("type"),
    MULTIPLE("multiple"),
    OPTIONS("options"),
    DESCRIPTION("description"),
    RESPONSE("response"),
    STATE("state"),
    STARTED_AT("startedAt"),
    /** Sub-agent type (SUBAGENT row). */
    AGENT_TYPE("agentType"),
    /** Sub-agent reference/identifier (SUBAGENT row). */
    AGENT_REF("agentRef"),
    DURATION_MS("durationMs"),
    MODEL_NAME("modelName"),
    /** Id of the parent tool call that triggered this sub-agent (SUBAGENT row; shared by parallel sub-agents in the same batch). */
    PARENT_TOOL_CALL_ID("parentToolCallId"),
    /** User message image attachment references ([{fileKey, contentType}]; the frontend fetches bytes by fileKey to display them). */
    IMAGES("images"),
    /** Usage aggregation ({promptTokens, completionTokens, totalTokens, cacheHitTokens, cacheMissTokens}; assistant / run_status rows). */
    USAGE("usage"),
    FEEDBACK("feedback"),
    /** Tool name (TOOL row; lets the frontend recognize internal protocol tools such as finish/builtin_12). */
    TOOL_NAME("toolName"),
    /** Standardized tool metadata (durationMs / statusCode / chars / exitCode / server …). */
    META("meta");

    private final String code;

    TimelineContentKey(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}