package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequestDTO implements Serializable {
    private String model;
    private boolean stream;
    private List<ChatMessageDTO> messages;
    private List<ToolDefinitionDTO> tools;
    @JsonProperty("tool_choice")
    @Getter(AccessLevel.NONE)
    private Object toolChoice;
    @JsonProperty("response_format")
    private ResponseFormatDTO responseFormat;
    @JsonProperty("thinking")
    private ThinkingDTO thinking;
    @JsonProperty("enable_thinking")
    private Boolean enableThinking;
    @JsonProperty("thinking_budget")
    private Integer thinkingBudget;
    @JsonProperty("tool_stream")
    private Boolean toolStream;
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
    @JsonProperty("stream_options")
    private StreamOptionsDTO streamOptions;
    /** Request deterministic output (supported by OpenAI and others; DeepSeek/GLM do not support it, the compatibility layer strips it). */
    private Long seed;
    @JsonProperty("logprobs")
    private Boolean logprobs;
    @JsonProperty("top_logprobs")
    private Integer topLogprobs;
    /** Number of candidates to sample; >1 makes the API return an array (this system uses 1 and only passes it through). */
    private Integer n;
    /** Per-token logit bias (OpenAI bitmap encoding; the compatibility layer passes or strips it per provider). */
    @JsonProperty("logit_bias")
    private Map<String, Integer> logitBias;
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;
    /** Caller identifier (audit/telemetry; filled in by the caller layer with the current user). */
    private String user;
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;
    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;

    @JsonProperty("tool_choice")
    public Object getToolChoice() {
        if (tools == null || tools.isEmpty()) return null;
        return toolChoice;
    }
}
