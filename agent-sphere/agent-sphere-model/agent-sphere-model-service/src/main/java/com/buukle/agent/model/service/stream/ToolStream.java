package com.buukle.agent.model.service.stream;

import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Consumer;

public class ToolStream {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Consumer<LLMEvent> onEvent;
    private final Map<Integer, PendingTool> pending = new LinkedHashMap<>();

    public ToolStream(Consumer<LLMEvent> onEvent) {
        this.onEvent = onEvent;
    }

    public void onDelta(int index, String id, String name, String argumentsFragment) {
        PendingTool current = pending.get(index);
        if (current == null) {
            if (id == null || name == null) return;
            current = new PendingTool(id, name, new StringBuilder());
            pending.put(index, current);
            onEvent.accept(new LLMEvent.ToolInputStart(id, name));
        }
        if (argumentsFragment != null && !argumentsFragment.isEmpty()) {
            current.arguments.append(argumentsFragment);
            onEvent.accept(new LLMEvent.ToolInputDelta(argumentsFragment));
        }
    }

    public List<LLMEvent.ToolCall> finishAll() {
        List<LLMEvent.ToolCall> calls = new ArrayList<>();
        for (PendingTool pt : pending.values()) {
            String args = pt.arguments.toString();
            onEvent.accept(new LLMEvent.ToolInputEnd(pt.id, pt.name));
            onEvent.accept(new LLMEvent.ToolCall(pt.id, pt.name, args));
            calls.add(new LLMEvent.ToolCall(pt.id, pt.name, args));
        }
        pending.clear();
        return calls;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private record PendingTool(String id, String name, StringBuilder arguments) {}
}
