package com.buukle.agent.runtime.kernel.contract;

import java.util.List;

public record TurnResult(String content, List<TurnToolCall> toolCalls, TurnOutcome outcome, String errorMessage) {

    public TurnResult {
        toolCalls = toolCalls != null ? toolCalls : List.of();
    }

    public static TurnResult complete(String content) {
        return new TurnResult(content, List.of(), TurnOutcome.COMPLETE, null);
    }

    public static TurnResult toolCalls(String content, List<TurnToolCall> calls) {
        return new TurnResult(content, calls, TurnOutcome.TOOL_CALLS, null);
    }

    public static TurnResult compacted() {
        return new TurnResult(null, List.of(), TurnOutcome.COMPACTED, null);
    }

    public static TurnResult error(String message) {
        return new TurnResult(null, List.of(), TurnOutcome.ERROR, message);
    }

    public static TurnResult cancelled(String content, List<TurnToolCall> calls) {
        return new TurnResult(content, calls, TurnOutcome.CANCELLED, null);
    }
}
