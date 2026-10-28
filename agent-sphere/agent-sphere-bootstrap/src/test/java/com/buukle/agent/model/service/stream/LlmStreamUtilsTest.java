package com.buukle.agent.model.service.stream;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * reasoning 字段名双兼容：vLLM/Qwen3 用 {@code reasoning}，DeepSeek/智谱用 {@code reasoning_content}。
 *
 * <p>线上 54 个 Qwen3.8 分片里 {@code reasoning_content} 出现 0 次、{@code reasoning} 才是实际字段，
 * 旧代码只认前者，导致 reasoningLen 恒为 0、思考正文丢失。
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
