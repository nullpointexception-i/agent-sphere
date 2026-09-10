package com.buukle.agent.model.dtvo.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 归一化的调用用量（供应商无关），供落库与聚合统计使用。
 * 兼容各家的 usage 形状：
 * - DeepSeek：顶层 prompt_tokens / prompt_cache_hit_tokens / prompt_cache_miss_tokens，
 *   新版本还下沉到 prompt_tokens_details.cached_tokens；completion_tokens_details.reasoning_tokens 记思考占用。
 * - OpenAI：无顶层 hit/miss，命中数在 prompt_tokens_details.cached_tokens。
 */
@Data
public class TokenUsage implements Serializable {

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
    private Integer reasoningTokens;

    private static int intOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** 从供应商原始 usage {@code Map} 归一化；无任何用量字段时返回 null，其余字段按可得值填充。 */
    @SuppressWarnings("unchecked")
    public static TokenUsage from(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;
        int prompt = intOf(raw, "prompt_tokens");
        int completion = intOf(raw, "completion_tokens");
        int total = intOf(raw, "total_tokens");
        if (total == 0 && (prompt > 0 || completion > 0)) {
            total = prompt + completion;
        }
        if (prompt == 0 && completion == 0 && total == 0 && !raw.containsKey("prompt_tokens")
                && !raw.containsKey("completion_tokens") && !raw.containsKey("total_tokens")) {
            return null;
        }

        Map<String, Object> promptDetails = obj(raw, "prompt_tokens_details");
        Map<String, Object> completionDetails = obj(raw, "completion_tokens_details");
        boolean hasRawTopLevel = raw.containsKey("prompt_cache_hit_tokens")
                || raw.containsKey("prompt_cache_miss_tokens");

        int cacheHit = 0;
        if (raw.containsKey("prompt_cache_hit_tokens")) {
            cacheHit = intOf(raw, "prompt_cache_hit_tokens");
        } else if (promptDetails.containsKey("cached_tokens")) {
            cacheHit = intOf(promptDetails, "cached_tokens");
        }
        int cacheMiss = 0;
        if (raw.containsKey("prompt_cache_miss_tokens")) {
            cacheMiss = intOf(raw, "prompt_cache_miss_tokens");
        } else if (hasRawTopLevel || promptDetails.containsKey("cached_tokens")) {
            cacheMiss = Math.max(0, prompt - cacheHit);
        }
        int reasoning = 0;
        if (completionDetails.containsKey("reasoning_tokens")) {
            reasoning = intOf(completionDetails, "reasoning_tokens");
        } else if (promptDetails.containsKey("reasoning_tokens")) {
            reasoning = intOf(promptDetails, "reasoning_tokens");
        }

        TokenUsage u = new TokenUsage();
        u.setPromptTokens(prompt);
        u.setCompletionTokens(completion);
        u.setTotalTokens(total);
        if (cacheHit > 0 || cacheMiss > 0 || raw.containsKey("prompt_cache_hit_tokens")
                || raw.containsKey("prompt_cache_miss_tokens") || promptDetails.containsKey("cached_tokens")) {
            u.setCacheHitTokens(cacheHit);
            u.setCacheMissTokens(cacheMiss);
        }
        if (reasoning > 0) {
            u.setReasoningTokens(reasoning);
        }
        return u;
    }
}