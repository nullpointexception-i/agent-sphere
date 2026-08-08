package com.buukle.agent.completions.dtvo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * agent_completions.config 支持的请求参数（与 ChatCompletionRequestDTO 对齐）。
 * 未知字段忽略；未设置的字段保持 null。
 */
@Data
public class CompletionsConfigDTO implements Serializable {
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
    /** 思考开关：支持布尔（false/true）或字符串（"disabled"/"enabled"），Jackson 布尔会强转成 "false"/"true" */
    private String thinking;
    private String reasoning;
}
