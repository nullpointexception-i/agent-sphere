package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigDTO;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigMapper;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * agent 链路请求参数接线：全局默认（系统配置 llm.defaults-config）为基底，
 * 实例级 config 字段级覆盖（实例优先），合并结果应用到请求。
 * 与 completions 层共用 {@link LlmSamplingConfigMapper}，保证同一套 JSON 语义一致。
 */
@Component
@RequiredArgsConstructor
public class LlmRequestConfigurer {

    private final SystemConfigSpi systemConfigSpi;

    /** 全局兜底 + 实例 config 合并后应用到请求。 */
    public void apply(ChatCompletionRequestDTO request, String instanceConfigJson) {
        String globalConfig = systemConfigSpi.get(SystemConfigKeys.LLM_DEFAULTS_CONFIG, "");
        LlmSamplingConfigDTO merged = LlmSamplingConfigMapper.merge(globalConfig, instanceConfigJson);
        if (merged != null) {
            LlmSamplingConfigMapper.apply(request, merged);
        }
        // usage 观测默认开启（仅当配置未显式设置时兜底）：供应商不支持会忽略该字段
        if (request.getStreamOptions() == null) {
            request.setStreamOptions(new com.buukle.agent.model.dtvo.dto.complete.StreamOptionsDTO().setIncludeUsage(true));
        } else if (request.getStreamOptions().getIncludeUsage() == null) {
            request.getStreamOptions().setIncludeUsage(true);
        }
    }

    /** 仅全局默认（标题 / 压缩等辅助调用的统一兜底）。 */
    public void applyGlobal(ChatCompletionRequestDTO request) {
        apply(request, null);
    }
}