package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigDTO;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigMapper;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LlmSamplingConfigDTO 共享映射层：merge（实例覆盖全局）与 apply 到请求。 */
class LlmSamplingConfigMapperTest {

    @Test
    void merge_instanceOverridesGlobal() {
        LlmSamplingConfigDTO merged = LlmSamplingConfigMapper.merge(
                "{\"temperature\":0.7,\"max_tokens\":1024,\"seed\":42}",
                "{\"temperature\":0.2}");

        assertNotNull(merged);
        assertEquals(0.2, merged.getTemperature(), 0.001, "实例应覆盖全局温度");
        assertEquals(1024, merged.getMaxTokens(), "全局未覆盖字段应保留");
        assertEquals(42L, merged.getSeed(), "全局独有字段应保留");
    }

    @Test
    void merge_bothBlank_returnsNull() {
        assertNull(LlmSamplingConfigMapper.merge("", null));
        assertNull(LlmSamplingConfigMapper.merge("not-json", "not-json"));
    }

    @Test
    void apply_mapsAllFields() {
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();
        LlmSamplingConfigDTO cfg = LlmSamplingConfigMapper.fromJson(
                "{\"temperature\":0.1,\"max_tokens\":2048,\"top_p\":0.9," +
                        "\"presence_penalty\":0.3,\"frequency_penalty\":0.2,\"stop\":[\"END\"]," +
                        "\"thinking\":true,\"budget_tokens\":8192,\"parallel_tool_calls\":true," +
                        "\"seed\":7,\"include_usage\":true,\"reasoning_effort\":\"medium\"}");

        LlmSamplingConfigMapper.apply(request, cfg);

        assertEquals(0.1, request.getTemperature(), 0.001);
        assertEquals(2048, request.getMaxTokens());
        assertEquals(0.9, request.getTopP(), 0.001);
        assertEquals(0.3, request.getPresencePenalty(), 0.001);
        assertEquals(0.2, request.getFrequencyPenalty(), 0.001);
        assertEquals(java.util.List.of("END"), request.getStop());
        assertTrue(request.getParallelToolCalls());
        assertEquals(7L, request.getSeed());
        assertEquals("medium", request.getReasoningEffort());
        assertNotNull(request.getStreamOptions());
        assertTrue(request.getStreamOptions().getIncludeUsage());
        ThinkingDTO thinking = request.getThinking();
        assertNotNull(thinking);
        assertEquals("enabled", thinking.getType());
        assertEquals(8192, thinking.getBudgetTokens());
    }

    @Test
    void apply_noThinkingWhenDisabled() {
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();
        LlmSamplingConfigDTO cfg = new LlmSamplingConfigDTO();
        cfg.setThinking("false");
        LlmSamplingConfigMapper.apply(request, cfg);
        assertEquals("disabled", request.getThinking().getType());
    }

    @Test
    void apply_unknownThinkingIgnored() {
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();
        LlmSamplingConfigDTO cfg = new LlmSamplingConfigDTO();
        cfg.setThinking("auto");
        LlmSamplingConfigMapper.apply(request, cfg);
        assertNull(request.getThinking(), "未知 thinking 取值不应设置");
    }

    @Test
    void apply_nullConfigIsNoop() {
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO().setModel("m");
        LlmSamplingConfigMapper.apply(request, null);
        LlmSamplingConfigMapper.apply(request, new LlmSamplingConfigDTO());
        assertEquals("m", request.getModel());
        assertNull(request.getTemperature());
    }
}