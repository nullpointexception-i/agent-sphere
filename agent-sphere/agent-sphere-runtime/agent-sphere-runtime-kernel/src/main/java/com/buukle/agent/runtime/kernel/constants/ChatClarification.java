package com.buukle.agent.runtime.kernel.constants;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;

public final class ChatClarification {
    public static final String CLARIFICATION_RESUME_PREFIX = "[User Response to Clarification — execute the next step now, do NOT ask another question]: ";
    public static final String INTERNAL_NAME = "ask_clarification";
    public static final String DISPLAY_NAME = "Ask Clarification";
    public static final String DISPLAY_NAME_CN = "请求澄清";
    public static final String DESCRIPTION = "CRITICAL: Whenever you need the user's input, decision, or confirmation (e.g., choose between options, confirm an action, or provide missing information), you MUST call this tool. NEVER ask questions in plain text — the user can only respond through this tool. Use type=choice for options, confirm for yes/no, input for free-form answer. AFTER receiving the user's response, IMMEDIATELY execute the next step — call the necessary tools and provide the result. Do NOT ask another clarification or question unless it is about a completely different missing piece of information. Never ask the user 'what do you want to do'.";

    public static final String CLARIFYING_ERROR_NOT_FOUND = "No pending clarification found for this run";
    public static final String CLARIFYING_SQL_LIMIT = "LIMIT 1";
    public static final String CLARIFYING_DEFAULT_TYPE = "confirm";

    public static final String CLARIFYING_STATUS_AWAITING_USER = "awaiting_user";
    public static final String CLARIFYING_JSON_STATUS = "status";
    public static final String CLARIFYING_JSON_CLARIFICATION_ID = "clarification_id";
    public static final String CLARIFICATION_RESPONSE_DISMISSED = "__dismissed__";
    public static final String CLARIFICATION_TOOL_NAME = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.ASK_CLARIFICATION.getId();

    private ChatClarification() {
    }
}
