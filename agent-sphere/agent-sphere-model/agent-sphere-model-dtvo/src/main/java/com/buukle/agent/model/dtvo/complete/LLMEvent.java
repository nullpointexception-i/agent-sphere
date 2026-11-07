package com.buukle.agent.model.dtvo.complete;

import java.util.Map;

public sealed interface LLMEvent {

    record TextDelta(String text) implements LLMEvent {
    }

    record ReasoningDelta(String text) implements LLMEvent {
    }

    record ToolInputStart(String id, String name) implements LLMEvent {
    }

    record ToolInputDelta(String text) implements LLMEvent {
    }

    record ToolInputEnd(String id, String name) implements LLMEvent {
    }

    record ToolCall(String id, String name, String arguments) implements LLMEvent {
    }

    record ToolResult(String id, String name, String result) implements LLMEvent {
    }

    record Finish(String reason, Map<String, Object> usage) implements LLMEvent {
    }

    /**
     * Call failure event.
     *
     * @param message    error text shown to the caller/user
     * @param httpStatus upstream HTTP status code (null when no HTTP exchange happened, e.g. connect timeout)
     * @param retryable whether a retry within the same turn is worthwhile: 4xx (except 408/429) are terminal errors,
     *                  and retrying only multiplies one failure into four (a production 400 was retried 3 times)
     */
    record Error(String message, Integer httpStatus, boolean retryable) implements LLMEvent {
        public Error(String message) {
            this(message, null, true);
        }
    }
}
