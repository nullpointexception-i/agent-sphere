package com.buukle.agent.model.service.stream;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Dual-compatible reasoning field names: vLLM/Qwen3 use {@code reasoning}, DeepSeek/Zhipu use {@code reasoning_content}.
 *
 * <p>In 54 production Qwen3.8 chunks, {@code reasoning_content} appeared 0 times and {@code reasoning} was the actual field;
 * the old code only recognized the former, so reasoningLen stayed 0 and the reasoning text was lost.
 */
class LlmStreamUtilsTest {

    private static JsonNode node(String json) {
        return JsonUtils.parse(json, JsonNode.class);
    }

    @Test
    void prefersVllmReasoningField() {
        assertEquals("thinking",
                LlmStreamUtils.firstTextual(node("{\"reasoning\":\"thinking\"}"),
                        "reasoning", "reasoning_content"));
    }

    @Test
    void fallsBackToReasoningContent() {
        assertEquals("thinking",
                LlmStreamUtils.firstTextual(node("{\"reasoning_content\":\"thinking\"}"),
                        "reasoning", "reasoning_content"));
    }

    @Test
    void skipsEmptyTextualSoNextFieldWins() {
        assertEquals("real",
                LlmStreamUtils.firstTextual(node("{\"reasoning\":\"\",\"reasoning_content\":\"real\"}"),
                        "reasoning", "reasoning_content"));
    }

    @Test
    void returnsNullWhenMissingOrNonTextual() {
        assertNull(LlmStreamUtils.firstTextual(node("{\"reasoning\":1}"),
                "reasoning", "reasoning_content"));
        assertNull(LlmStreamUtils.firstTextual(null, "reasoning"));
    }
}
