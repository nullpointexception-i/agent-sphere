package com.buukle.agent.runtime.kernel.model.invoke;

import org.springframework.context.ApplicationEvent;

public class LlmInteractionEvent extends ApplicationEvent {

    private final LlmInteractionMeta meta;
    private final String requestBody;
    private final String responseBody;
    private final String modelName;
    private final long durationMs;
    private final boolean success;
    private final String errorMessage;

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, long durationMs,
                               boolean success, String errorMessage) {
        super(source);
        this.meta = meta;
        this.modelName = modelName;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
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
