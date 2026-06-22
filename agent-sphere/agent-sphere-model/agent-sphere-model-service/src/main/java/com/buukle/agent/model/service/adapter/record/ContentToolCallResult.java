package com.buukle.agent.model.service.adapter.record;

import java.util.List;

public record ContentToolCallResult(String cleanedContent, List<ContentToolCall> toolCalls) {

    public static final ContentToolCallResult EMPTY = new ContentToolCallResult(null, List.of());

    public boolean isEmpty() {
        return toolCalls == null || toolCalls.isEmpty();
    }

    public record ContentToolCall(String name, String arguments) {}
}
