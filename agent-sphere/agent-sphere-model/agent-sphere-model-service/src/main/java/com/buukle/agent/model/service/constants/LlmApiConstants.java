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
    // Values
    public static final String FINISH_REASON_STOP = "stop";

    private LlmApiConstants() {
    }

}
