package com.buukle.agent.model.dtvo.dto;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.StreamOptionsDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LlmSamplingConfigDTO → ChatCompletionRequestDTO 的共享映射工具（纯静态，无 Spring 依赖）。
 * 供 agent 主/子链路、completions 层与全局兜底共用，保证同一套 config JSON 语义一致。
 */
public final class LlmSamplingConfigMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String THINKING_ENABLED = "enabled";
    private static final String THINKING_DISABLED = "disabled";

    private LlmSamplingConfigMapper() {
    }

    /** 解析 config JSON；空/非法返回 null。 */
    public static LlmSamplingConfigDTO fromJson(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            return JSON.readValue(configJson, LlmSamplingConfigDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 字段级合并：global 为基底，instance 非空字段覆盖（实例优先，全局兜底）；两者皆空返回 null。 */
    public static LlmSamplingConfigDTO merge(String globalConfigJson, String instanceConfigJson) {
        LlmSamplingConfigDTO merged = fromJson(globalConfigJson);
        LlmSamplingConfigDTO instance = fromJson(instanceConfigJson);
        if (merged == null && instance == null) return null;
        if (merged == null) merged = new LlmSamplingConfigDTO();
        if (instance == null) return merged;
        if (instance.getTemperature() != null) merged.setTemperature(instance.getTemperature());
        if (instance.getMaxTokens() != null) merged.setMaxTokens(instance.getMaxTokens());
        if (instance.getTopP() != null) merged.setTopP(instance.getTopP());
        if (instance.getPresencePenalty() != null) merged.setPresencePenalty(instance.getPresencePenalty());
        if (instance.getFrequencyPenalty() != null) merged.setFrequencyPenalty(instance.getFrequencyPenalty());
        if (instance.getStop() != null) merged.setStop(instance.getStop());
        if (instance.getThinking() != null) merged.setThinking(instance.getThinking());
        if (instance.getReasoning() != null) merged.setReasoning(instance.getReasoning());
        if (instance.getBudgetTokens() != null) merged.setBudgetTokens(instance.getBudgetTokens());
        if (instance.getSeed() != null) merged.setSeed(instance.getSeed());
        if (instance.getReasoningEffort() != null) merged.setReasoningEffort(instance.getReasoningEffort());
        if (instance.getMaxCompletionTokens() != null) merged.setMaxCompletionTokens(instance.getMaxCompletionTokens());
        if (instance.getParallelToolCalls() != null) merged.setParallelToolCalls(instance.getParallelToolCalls());
        if (instance.getN() != null) merged.setN(instance.getN());
        if (instance.getLogprobs() != null) merged.setLogprobs(instance.getLogprobs());
        if (instance.getTopLogprobs() != null) merged.setTopLogprobs(instance.getTopLogprobs());
        if (instance.getLogitBias() != null) merged.setLogitBias(instance.getLogitBias());
        if (instance.getIncludeUsage() != null) merged.setIncludeUsage(instance.getIncludeUsage());
        if (instance.getUser() != null) merged.setUser(instance.getUser());
        return merged;
    }

    /** 将配置映射到请求（仅设置非空字段）。 */
    public static void apply(ChatCompletionRequestDTO request, LlmSamplingConfigDTO cfg) {
        if (request == null || cfg == null) return;
        if (cfg.getTemperature() != null) request.setTemperature(cfg.getTemperature());
        if (cfg.getMaxTokens() != null) request.setMaxTokens(cfg.getMaxTokens());
        if (cfg.getTopP() != null) request.setTopP(cfg.getTopP());
        if (cfg.getPresencePenalty() != null) request.setPresencePenalty(cfg.getPresencePenalty());
        if (cfg.getFrequencyPenalty() != null) request.setFrequencyPenalty(cfg.getFrequencyPenalty());
        if (cfg.getStop() != null && !cfg.getStop().isEmpty()) request.setStop(cfg.getStop());
        String thinkingValue = cfg.getThinking() != null && !cfg.getThinking().isBlank()
                ? cfg.getThinking() : cfg.getReasoning();
        String thinkingType = resolveThinkingType(thinkingValue);
        if (thinkingType != null && !thinkingType.isBlank()) {
            ThinkingDTO thinking = new ThinkingDTO().setType(thinkingType);
            if (cfg.getBudgetTokens() != null) thinking.setBudgetTokens(cfg.getBudgetTokens());
            request.setThinking(thinking);
        }
        if (cfg.getSeed() != null) request.setSeed(cfg.getSeed());
        if (cfg.getReasoningEffort() != null) request.setReasoningEffort(cfg.getReasoningEffort());
        if (cfg.getMaxCompletionTokens() != null) request.setMaxCompletionTokens(cfg.getMaxCompletionTokens());
        if (cfg.getParallelToolCalls() != null) request.setParallelToolCalls(cfg.getParallelToolCalls());
        if (cfg.getN() != null) request.setN(cfg.getN());
        if (cfg.getLogprobs() != null) request.setLogprobs(cfg.getLogprobs());
        if (cfg.getTopLogprobs() != null) request.setTopLogprobs(cfg.getTopLogprobs());
        if (cfg.getLogitBias() != null && !cfg.getLogitBias().isEmpty()) request.setLogitBias(cfg.getLogitBias());
        if (cfg.getIncludeUsage() != null) {
            request.setStreamOptions(new StreamOptionsDTO().setIncludeUsage(cfg.getIncludeUsage()));
        }
        if (cfg.getUser() != null) request.setUser(cfg.getUser());
    }

    /** 归一化 thinking 取值：/"enabled"/true/开 → "enabled"；/"disabled"/关 → "disabled"；其余不设置。 */
    public static String resolveThinkingType(String value) {
        if (value == null || value.isBlank()) return null;
        if (THINKING_ENABLED.equalsIgnoreCase(value)
                || Boolean.TRUE.toString().equalsIgnoreCase(value)) {
            return THINKING_ENABLED;
        }
        if (THINKING_DISABLED.equalsIgnoreCase(value)
                || Boolean.FALSE.toString().equalsIgnoreCase(value)) {
            return THINKING_DISABLED;
        }
        return null;
    }
}