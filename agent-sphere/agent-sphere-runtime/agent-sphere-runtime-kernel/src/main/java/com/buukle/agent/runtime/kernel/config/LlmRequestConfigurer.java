package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigDTO;
import com.buukle.agent.model.dtvo.dto.LlmSamplingConfigMapper;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * agent 链路请求参数接线：全局默认（系统配置 llm.defaults-config）为基底，
 * 实例级 config 字段级覆盖（实例优先），合并结果应用到请求。
 * 与 completions 层共用 {@link LlmSamplingConfigMapper}，保证同一套 JSON 语义一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRequestConfigurer {

    private static final String THINKING_ENABLED = "enabled";

    private final SystemConfigSpi systemConfigSpi;
    private final AgentRuntimeProperties properties;

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

    /**
     * 主/子 Agent 聊天请求：在 {@link #apply} 基础上，按本次实际输出上限补默认 reasoning budget。
     *
     * <p>优先级：显式 budget_tokens（含 0=关闭） &gt; 动态默认 &gt; 不设置。
     * 动态值只划分已有输出预算，不会抬高 max_tokens。
     */
    public void applyChat(ChatCompletionRequestDTO request,
                          String instanceConfigJson,
                          Integer outputCap,
                          String company) {
        apply(request, instanceConfigJson);

        AgentRuntimeProperties.LlmConfig.ReasoningBudgetConfig cfg =
                properties.getLlm().getReasoningBudget();
        if (cfg == null || !cfg.isEnabled() || !supportsCompany(cfg, company)) {
            return;
        }
        applyReasoningBudget(request, outputCap, cfg);
    }

    /** 仅全局默认（标题 / 压缩等辅助调用的统一兜底）。 */
    public void applyGlobal(ChatCompletionRequestDTO request) {
        apply(request, null);
    }

    /** 合并后的输出 token 上限（实例优先、全局兜底）；未配置返回 null（预算回落到比例估算）。 */
    public Integer resolveMaxOutputTokens(String instanceConfigJson) {
        String globalConfig = systemConfigSpi.get(SystemConfigKeys.LLM_DEFAULTS_CONFIG, "");
        LlmSamplingConfigDTO merged = LlmSamplingConfigMapper.merge(globalConfig, instanceConfigJson);
        if (merged == null) {
            return null;
        }
        return merged.getMaxTokens() != null ? merged.getMaxTokens() : merged.getMaxCompletionTokens();
    }

    private void applyReasoningBudget(ChatCompletionRequestDTO request,
                                      Integer outputCap,
                                      AgentRuntimeProperties.LlmConfig.ReasoningBudgetConfig cfg) {
        ThinkingDTO thinking = request.getThinking();
        if (thinking == null || !THINKING_ENABLED.equalsIgnoreCase(thinking.getType())) {
            return;
        }

        Integer cap = firstPositive(outputCap, request.getMaxTokens());
        if (cap == null) {
            return;
        }
        int answerFloor = answerFloor(cap, cfg);
        int upper = Math.min(cfg.getMaxTokens(), cap - answerFloor);

        Integer explicit = thinking.getBudgetTokens();
        if (explicit != null) {
            // 0/负数表示显式关闭：不注入，也不回落到动态默认。
            if (explicit <= 0) {
                thinking.setBudgetTokens(null);
                return;
            }
            if (upper <= 0) {
                log.warn("Skip reasoning budget: outputCap={} leaves no room after answer floor={}", cap, answerFloor);
                thinking.setBudgetTokens(null);
                return;
            }
            if (explicit > upper) {
                log.warn("Clamp reasoning budget from {} to {} (outputCap={}, answerFloor={})",
                        explicit, upper, cap, answerFloor);
                thinking.setBudgetTokens(upper);
            }
            return;
        }

        if (upper < cfg.getMinTokens()) {
            return;
        }
        int budget = (int) Math.floor(cap * cfg.getRatio());
        budget = Math.max(cfg.getMinTokens(), Math.min(budget, upper));
        thinking.setBudgetTokens(budget);
    }

    private static int answerFloor(int cap,
                                   AgentRuntimeProperties.LlmConfig.ReasoningBudgetConfig cfg) {
        return Math.max(cfg.getMinTokens(), (int) Math.ceil(cap * cfg.getMinAnswerRatio()));
    }

    private static Integer firstPositive(Integer first, Integer second) {
        if (first != null && first > 0) {
            return first;
        }
        if (second != null && second > 0) {
            return second;
        }
        return null;
    }

    private static boolean supportsCompany(AgentRuntimeProperties.LlmConfig.ReasoningBudgetConfig cfg,
                                           String company) {
        if (company == null || cfg.getProviders() == null) {
            return false;
        }
        return cfg.getProviders().stream().anyMatch(p -> p.equalsIgnoreCase(company));
    }
}
