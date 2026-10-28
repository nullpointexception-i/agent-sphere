package com.buukle.agent.runtime.kernel.contract;

import java.util.List;

public record TurnResult(String content, List<TurnToolCall> toolCalls, TurnOutcome outcome, String errorMessage,
                         String finishReason, CompactionOutcome compactionOutcome) {

    public TurnResult {
        toolCalls = toolCalls != null ? toolCalls : List.of();
    }

    /** 携带本次流式响应的真实 finish_reason（stop/length/content_filter/tool_calls）。 */
    public TurnResult withFinishReason(String reason) {
        return new TurnResult(content, toolCalls, outcome, errorMessage, reason, compactionOutcome);
    }

    public static TurnResult complete(String content) {
        return new TurnResult(content, List.of(), TurnOutcome.COMPLETE, null, null, null);
    }

    public static TurnResult toolCalls(String content, List<TurnToolCall> calls) {
        return new TurnResult(content, calls, TurnOutcome.TOOL_CALLS, null, null, null);
    }

    public static TurnResult compacted(CompactionOutcome outcome) {
        return new TurnResult(null, List.of(), TurnOutcome.COMPACTED, null, null, outcome);
    }

    public static TurnResult error(String message) {
        return new TurnResult(null, List.of(), TurnOutcome.ERROR, message, null, null);
    }

    public static TurnResult cancelled(String content, List<TurnToolCall> calls) {
        return new TurnResult(content, calls, TurnOutcome.CANCELLED, null, null, null);
    }
}
