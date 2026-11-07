package com.buukle.agent.runtime.kernel.model.invoke;

import com.buukle.agent.model.dtvo.complete.LlmStreamStats;
import com.buukle.agent.model.dtvo.dto.TokenUsage;
import org.springframework.context.ApplicationEvent;

public class LlmInteractionEvent extends ApplicationEvent {

    private final LlmInteractionMeta meta;
    private final String requestBody;
    private final String responseBody;
    private final String reply;
    private final String reasoning;
    private final String modelName;
    private final TokenUsage usage;
    private final long durationMs;
    private final boolean success;
    private final String errorMessage;
    /** Real termination reason (stop/length/content_filter/tool_calls); null = the stream did not provide one. */
    private String finishReason;
    /** Provider transport-layer retry statistics; null = not collected. */
    private LlmStreamStats streamStats;

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, long durationMs,
                               boolean success, String errorMessage) {
        this(source, meta, modelName, requestBody, responseBody, null, null, null, durationMs, success, errorMessage);
    }

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, String reasoning,
                               long durationMs, boolean success, String errorMessage) {
        this(source, meta, modelName, requestBody, responseBody, null, reasoning, null, durationMs, success, errorMessage);
    }

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, String reply, String reasoning,
                               long durationMs, boolean success, String errorMessage) {
        this(source, meta, modelName, requestBody, responseBody, reply, reasoning, null, durationMs, success, errorMessage);
    }

    public LlmInteractionEvent(Object source, LlmInteractionMeta meta, String modelName,
                               String requestBody, String responseBody, String reply, String reasoning,
                               TokenUsage usage, long durationMs, boolean success, String errorMessage) {
        super(source);
        this.meta = meta;
        this.modelName = modelName;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.reply = reply;
        this.reasoning = reasoning;
        this.usage = usage;
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

    public TokenUsage getUsage() {
        return usage;
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

    public String getFinishReason() {
        return finishReason;
    }

    /** Chained setter for the termination reason, avoiding another overloaded constructor for an audit field. */
    public LlmInteractionEvent withFinishReason(String finishReason) {
        this.finishReason = finishReason;
        return this;
    }

    public LlmStreamStats getStreamStats() {
        return streamStats;
    }

    /** Chained setter for provider transport-layer retry statistics. */
    public LlmInteractionEvent withStreamStats(LlmStreamStats streamStats) {
        this.streamStats = streamStats;
        return this;
    }
}
