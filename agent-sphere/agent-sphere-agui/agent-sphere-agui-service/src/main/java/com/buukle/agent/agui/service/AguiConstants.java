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
    public static final String FIELD_SNAPSHOT = "snapshot";
    public static final String FIELD_TODOS = "todos";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_PRIORITY = "priority";
    public static final String FIELD_SESSION_ID = "sessionId";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_VALUE = "value";

    // AG-UI CUSTOM 事件（会话标题更新）常量
    public static final String CUSTOM_EVENT_SESSION_TITLE_UPDATED = "session_title_updated";

    // AG-UI 事件值字面量
    public static final String OUTCOME_TYPE_SUCCESS = "success";
    public static final String OUTCOME_TYPE_INTERRUPT = "interrupt";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_REASONING = "reasoning";
    public static final String ERROR_MESSAGE_RUN_FAILED = "Run failed";
    public static final String ERROR_MESSAGE_RUN_CANCELLED = "Run cancelled";
    public static final String DEFAULT_TOOL_NAME = "unknown";

    // RUN_FINISHED interrupt 载荷字段
    public static final String FIELD_INTERRUPTS = "interrupts";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_METADATA = "metadata";
    public static final String FIELD_OPTIONS = "options";
    public static final String FIELD_ID = "id";
    public static final String FIELD_LABEL = "label";
    public static final String INTERRUPT_ID_FALLBACK = "clarification";

    // clarification 类型/状态字面量（对齐 ChatClarification）
    public static final String CLARIFICATION_TYPE_CONFIRM = "confirm";
    public static final String CLARIFICATION_TYPE_TEXT = "text";
    public static final String CLARIFICATION_RESPONSE_DISMISSED = "__dismissed__";

    // AG-UI resume 状态
    public static final String RESUME_STATUS_RESOLVED = "resolved";
    public static final String RESUME_STATUS_CANCELLED = "cancelled";

    // 消息/工具调用 ID 前缀
    public static final String TEXT_MESSAGE_ID_PREFIX = "msg-";
    public static final String REASONING_MESSAGE_ID_PREFIX = "reasoning-";
    public static final String TOOL_CALL_ID_FALLBACK_PREFIX = "tool-";
    public static final String TOOL_RESULT_MESSAGE_ID_PREFIX = "tool-result-";
}