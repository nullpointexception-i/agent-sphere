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
    /** 期望确定性输出（OpenAI 等支持；DeepSeek/GLM 等不支持，兼容层会剔除）。 */
    private Long seed;
    @JsonProperty("logprobs")
    private Boolean logprobs;
    @JsonProperty("top_logprobs")
    private Integer topLogprobs;
    /** 采样多少个候选；>1 时接口返回数组（本系统按 1 使用，仅透传）。 */
    private Integer n;
    /** 按 token 的 logit 偏置（OpenAI 位图编码；兼容层按 provider 透传/剔除）。 */
    @JsonProperty("logit_bias")
    private Map<String, Integer> logitBias;
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;
    /** 调用方标识（审计/遥测；由上层按当前用户填充）。 */
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
