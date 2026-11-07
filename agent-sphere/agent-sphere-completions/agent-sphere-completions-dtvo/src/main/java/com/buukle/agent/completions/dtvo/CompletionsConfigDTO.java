package com.buukle.agent.completions.dtvo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Request parameters supported by agent_completions.config (aligned with ChatCompletionRequestDTO).
 * Unknown fields are ignored; unset fields stay null.
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
    /** Thinking switch: accepts a boolean (false/true) or a string ("disabled"/"enabled"); Jackson coerces booleans to "false"/"true". */
    private String thinking;
    private String reasoning;
}
