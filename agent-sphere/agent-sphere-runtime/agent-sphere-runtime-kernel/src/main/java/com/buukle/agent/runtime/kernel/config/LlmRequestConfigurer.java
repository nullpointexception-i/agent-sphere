package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigDTO;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigMapper;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Request parameter wiring for the agent path: global defaults (system config llm.defaults-config) as the base,
 * overridden at field level by instance-level config (instance takes precedence); the merged result is applied to the request.
 * Shares {@link LlmSamplingConfigMapper} with the completions layer so the same JSON semantics stay consistent.
 */
@Component
@RequiredArgsConstructor
public class LlmRequestConfigurer {

    private final SystemConfigSpi systemConfigSpi;

    /** Merges global fallback + instance config and applies the result to the request. */
    public void apply(ChatCompletionRequestDTO request, String instanceConfigJson) {
        String globalConfig = systemConfigSpi.get(SystemConfigKeys.LLM_DEFAULTS_CONFIG, "");
        LlmSamplingConfigDTO merged = LlmSamplingConfigMapper.merge(globalConfig, instanceConfigJson);
        if (merged != null) {
            LlmSamplingConfigMapper.apply(request, merged);
        }
        // usage observation is on by default (a fallback only when the config does not set it explicitly): providers that do not support it ignore the field
        if (request.getStreamOptions() == null) {
            request.setStreamOptions(new com.buukle.agent.model.dtvo.dto.complete.StreamOptionsDTO().setIncludeUsage(true));
        } else if (request.getStreamOptions().getIncludeUsage() == null) {
            request.getStreamOptions().setIncludeUsage(true);
        }
    }

    /**
     * Main/sub-agent chat request: applies global + instance sampling config.
     *
     * <p>Reasoning effort is controlled uniformly by `reasoning_effort`; budget_tokens is no longer computed.
     */
    public void applyChat(ChatCompletionRequestDTO request, String instanceConfigJson) {
        apply(request, instanceConfigJson);
    }

    /** Global defaults only (uniform fallback for auxiliary calls such as title / compaction). */
    public void applyGlobal(ChatCompletionRequestDTO request) {
        apply(request, null);
    }

    /** Merged output token limit (instance takes precedence, global as fallback); returns null when unset (budget falls back to ratio estimation). */
    public Integer resolveMaxOutputTokens(String instanceConfigJson) {
        String globalConfig = systemConfigSpi.get(SystemConfigKeys.LLM_DEFAULTS_CONFIG, "");
        LlmSamplingConfigDTO merged = LlmSamplingConfigMapper.merge(globalConfig, instanceConfigJson);
        if (merged == null) {
            return null;
        }
        return merged.getMaxTokens() != null ? merged.getMaxTokens() : merged.getMaxCompletionTokens();
    }
}
