package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamOptionsDTO implements Serializable {
    /** Include usage at the end of a streaming response (supported by OpenAI / compatible implementations). */
    @JsonProperty("include_usage")
    private Boolean includeUsage;
}