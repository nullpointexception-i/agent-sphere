package com.buukle.agent.model.dtvo.complete;

import java.util.List;
import java.util.Map;

public sealed interface LLMEvent {

    record TextDelta(String text) implements LLMEvent {}
    record ReasoningDelta(String text) implements LLMEvent {}
    record ToolInputStart(String id, String name) implements LLMEvent {}
    record ToolInputDelta(String text) implements LLMEvent {}
    record ToolInputEnd(String id, String name) implements LLMEvent {}
    record ToolCall(String id, String name, String arguments) implements LLMEvent {}
    record ToolResult(String id, String name, String result) implements LLMEvent {}
    record Finish(String reason, Map<String, Object> usage) implements LLMEvent {}
}
