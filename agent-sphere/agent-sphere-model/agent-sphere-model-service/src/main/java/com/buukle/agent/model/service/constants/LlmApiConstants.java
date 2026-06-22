package com.buukle.agent.model.service.constants;

public final class LlmApiConstants {
    // URL
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    // Headers
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String APPLICATION_JSON = "application/json";
    public static final String TEXT_EVENT_STREAM = "text/event-stream";
    public static final String BEARER_PREFIX = "Bearer ";
    // SSE
    public static final String SSE_DATA_PREFIX = "data: ";
    public static final String SSE_DONE_MARKER = "[DONE]";
    // JSON fields
    public static final String FIELD_CHOICES = "choices";
    public static final String FIELD_DELTA = "delta";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_REASONING_CONTENT = "reasoning_content";
    public static final String FIELD_FINISH_REASON = "finish_reason";
    public static final String FIELD_TOOL_CALLS = "tool_calls";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_INDEX = "index";
    public static final String FIELD_ID = "id";
    public static final String FIELD_MESSAGE = "message";
    public static final String FINISH_REASON_STOP = "stop";
    // Content field names (for model output JSON parsing)
    public static final String FIELD_ACTION = "action";
    public static final String FIELD_TOOL = "tool";
    public static final String FIELD_FUNC = "func";
    public static final String FIELD_PARAMETERS = "parameters";
    public static final String FIELD_PARAMS = "params";
    public static final String FIELD_FUNC_ARGS = "func_args";
    public static final String FIELD_KWARGS = "kwargs";
    // Content wrapper markers (Zhipu thinking models)
    public static final String CONTENT_WRAPPER_BEGIN = "<|begin_of_box|>";
    public static final String CONTENT_WRAPPER_END = "<|end_of_box|>";
    // Call ID prefix
    public static final String PARSED_CALL_ID_PREFIX = "parsed_";
    // Empty JSON
    public static final String EMPTY_JSON_ARGS = "{}";

    private LlmApiConstants() {
    }

}
