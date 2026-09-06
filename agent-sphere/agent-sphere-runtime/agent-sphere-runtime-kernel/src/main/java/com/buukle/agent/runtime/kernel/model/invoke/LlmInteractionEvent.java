package com.buukle.agent.runtime.kernel.model.invoke;

import org.springframework.context.ApplicationEvent;

public class LlmInteractionEvent extends ApplicationEvent {

    private final LlmInteractionMeta meta;
    private final String requestBody;
    private final String responseBody;
    private final String reply;
    private final String reasoning;
    private final String modelName;
    private final long durationMs;
    private final boolean success;
    private final String errorMessage;

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, long durationMs,
                               boolean success, String errorMessage) {
        this(source, meta, modelName, requestBody, responseBody, null, null, durationMs, success, errorMessage);
    }

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, String reasoning,
                               long durationMs, boolean success, String errorMessage) {
        this(source, meta, modelName, requestBody, responseBody, null, reasoning, durationMs, success, errorMessage);
    }

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, String reply, String reasoning,
                               long durationMs, boolean success, String errorMessage) {
        super(source);
        this.meta = meta;
        this.modelName = modelName;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.reply = reply;
        this.reasoning = reasoning;
        this.durationMs = durationMs;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public LlmInteractionMeta getMeta() {
        return meta;
    }

    public String getModelName() {
        return modelName;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getReasoning() {
        return reasoning;
    }

    public String getReply() {
        return reply;
    }

    public Long getSubAgentRunId() {
        return meta != null ? meta.getSubAgentRunId() : null;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
