package com.buukle.agent.model.dtvo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON shape for sampling/request parameter config (aligned with provider wire fields).
 * Used in three places: agent_instance.config (instance level), agent_system_config llm.defaults-config
 * (global fallback), and agent_completions.config (completions layer). Aligned with and extends
 * {@link com.buukle.agent.completions.dtvo.CompletionsConfigDTO}.
 * Unset fields stay null and are dropped by the request DTO's NON_NULL strategy during serialization.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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
    /** Thinking switch: boolean (false/true) or string ("disabled"/"enabled"). */
    private String thinking;
    /** reasoning is a compatibility alias for thinking. */
    private String reasoning;
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
    /** Include usage in the streaming response (maps to request stream_options.include_usage). */
    @JsonProperty("include_usage")
    private Boolean includeUsage;
    /** Caller identifier (audit/telemetry). */
    private String user;
}