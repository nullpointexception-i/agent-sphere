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
     * 调用失败事件。
     *
     * @param message   给上层/用户看的错误文案
     * @param httpStatus 上游 HTTP 状态码（无 HTTP 交互时为 null，如连接超时）
     * @param retryable 是否值得在同一轮内重试：4xx（408/429 除外）属于终态错误，
     *                  反复重试只会把一次失败放大成 4 次（线上 400 被重试 3 次）
     */
    record Error(String message, Integer httpStatus, boolean retryable) implements LLMEvent {
        public Error(String message) {
            this(message, null, true);
        }
    }
}
