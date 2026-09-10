package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.dto.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** TokenUsage 归一化：兼容 DeepSeek 顶层 cache 计数 / OpenAI details 两种形状。 */
class TokenUsageTest {

    @Test
    void fromDeepSeekTopLevelCounters() {
        Map<String, Object> raw = Map.of(
                "prompt_tokens", 120,
                "prompt_cache_hit_tokens", 80,
                "prompt_cache_miss_tokens", 40,
                "completion_tokens", 30,
                "total_tokens", 150,
                "prompt_tokens_details", Map.of("cached_tokens", 80, "reasoning_tokens", 5));

        TokenUsage u = TokenUsage.from(raw);

        assertEquals(120, u.getPromptTokens());
        assertEquals(30, u.getCompletionTokens());
        assertEquals(150, u.getTotalTokens());
        assertEquals(80, u.getCacheHitTokens());
        assertEquals(40, u.getCacheMissTokens());
        assertEquals(5, u.getReasoningTokens());
    }

    @Test
    void fromOpenAiOnlyDetails() {
        Map<String, Object> raw = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 25,
                "total_tokens", 125,
                "prompt_tokens_details", Map.of("cached_tokens", 70));

        TokenUsage u = TokenUsage.from(raw);

        assertEquals(100, u.getPromptTokens());
        assertEquals(125, u.getTotalTokens());
        assertEquals(70, u.getCacheHitTokens());
        assertEquals(30, u.getCacheMissTokens(), "OpenAI 无 miss 顶层值时按 prompt-hit 推算");
        assertNull(u.getReasoningTokens(), "无 reasoning_tokens 时不填充");
    }

    @Test
    void fromEmptyOrNull_returnsNull() {
        assertNull(TokenUsage.from(null));
        assertNull(TokenUsage.from(Map.of()));
    }

    @Test
    void fromTotalMissing_fallsBackToSum() {
        Map<String, Object> raw = Map.of(
                "prompt_tokens", 60,
                "completion_tokens", 20);

        TokenUsage u = TokenUsage.from(raw);

        assertEquals(80, u.getTotalTokens());
    }
}