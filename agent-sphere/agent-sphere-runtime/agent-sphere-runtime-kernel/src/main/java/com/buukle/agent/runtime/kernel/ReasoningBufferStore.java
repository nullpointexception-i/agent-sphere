package com.buukle.agent.runtime.kernel;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-runId buffer of accumulated model reasoning (thinking) text; single-replica in-memory state.
 * A terminal run writes it once to agent_run.reasoning; the orphan sweep drains it to the DB before marking FAILED so reasoning is not lost.
 */
@Component
public class ReasoningBufferStore {

    private final Map<Long, StringBuilder> reasoningBuffers = new ConcurrentHashMap<>();

    public void accumulate(Long runId, String text) {
        if (runId == null || text == null || text.isBlank()) {
            return;
        }
        reasoningBuffers.computeIfAbsent(runId, k -> new StringBuilder()).append(text);
    }

    /** Takes and clears the reasoning accumulated for the run; returns null when there is no buffer. */
    public String drain(Long runId) {
        StringBuilder sb = reasoningBuffers.remove(runId);
        return sb == null || sb.length() == 0 ? null : sb.toString();
    }
}