package com.buukle.agent.model.dtvo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 采样/请求参数配置的共享 JSON 形状（与 provider wire 字段对齐）。
 * 三处共用：agent_instance.config（实例级）、agent_system_config llm.defaults-config（全局兜底）、
 * agent_completions.config（completions 层）。与 {@link com.buukle.agent.completions.dtvo.CompletionsConfigDTO} 对齐并扩展。
 * 未设置的字段保持 null，序列化时由请求 DTO 的 NON_NULL 策略剔除。
 */
@Data
public class LlmSamplingConfigDTO implements Serializable {
    private Double temperature;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    @JsonProperty("top_p")
    private Double topP;
    @JsonProperty("presence_penalty")
    private Double presencePenalty;
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;
    private List<String> stop;
    /** 思考开关：布尔（false/true）或字符串（"disabled"/"enabled"）。 */
    private String thinking;
    /** reasoning 为 thinking 的兼容别名。 */
    private String reasoning;
    /** DeepSeek 思考 token 配额，映射 ThinkingDTO.budget_tokens。 */
    @JsonProperty("budget_tokens")
    private Integer budgetTokens;
    private Long seed;
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;
    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;
    private Integer n;
    private Boolean logprobs;
    @JsonProperty("top_logprobs")
    private Integer topLogprobs;
    @JsonProperty("logit_bias")
    private Map<String, Integer> logitBias;
    /** 流式响应携带 usage（对应请求 stream_options.include_usage）。 */
    @JsonProperty("include_usage")
    private Boolean includeUsage;
    /** 调用方标识（审计/遥测）。 */
    private String user;
}