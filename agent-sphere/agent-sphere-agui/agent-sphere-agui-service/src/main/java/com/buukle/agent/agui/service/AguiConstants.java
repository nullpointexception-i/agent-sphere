package com.buukle.agent.agui.service;

/**
 * AG-UI runtime constants.
 */
public final class AguiConstants {

    private AguiConstants() {
    }

    public static final String DEFAULT_AGENT_DESCRIPTION = "Default agent";
    public static final String CAPABILITY_CHAT = "chat";
    public static final String DELIVERY_COPILOT = "copilot";

    // AG-UI 事件 JSON 字段键（对齐 @ag-ui/core EventType 事件的 data 载荷）
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_THREAD_ID = "threadId";
    public static final String FIELD_RUN_ID = "runId";
    public static final String FIELD_OUTCOME = "outcome";
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_MESSAGE_ID = "messageId";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_DELTA = "delta";
    public static final String FIELD_TOOL_CALL_ID = "toolCallId";
    public static final String FIELD_TOOL_CALL_NAME = "toolCallName";
    public static final String FIELD_CONTENT = "content";

    // AG-UI 事件值字面量
    public static final String OUTCOME_TYPE_SUCCESS = "success";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ERROR_MESSAGE_RUN_FAILED = "Run failed";
    public static final String ERROR_MESSAGE_RUN_CANCELLED = "Run cancelled";
    public static final String DEFAULT_TOOL_NAME = "unknown";

    // 消息/工具调用 ID 前缀
    public static final String TEXT_MESSAGE_ID_PREFIX = "msg-";
    public static final String REASONING_MESSAGE_ID_PREFIX = "reasoning-";
    public static final String TOOL_CALL_ID_FALLBACK_PREFIX = "tool-";
}