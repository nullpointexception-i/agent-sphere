package com.buukle.agent.runtime.kernel;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 runId 累积的模型推理（thinking）文本缓冲，单副本内存态。
 * 终态 run 一次性写入 agent_run.reasoning；孤儿清扫标记 FAILED 前先 drain 落库，避免推理丢失。
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

    /** 取出并清空该 run 已积累的推理；无缓冲返回 null。 */
    public String drain(Long runId) {
        StringBuilder sb = reasoningBuffers.remove(runId);
        return sb == null || sb.length() == 0 ? null : sb.toString();
    }
}