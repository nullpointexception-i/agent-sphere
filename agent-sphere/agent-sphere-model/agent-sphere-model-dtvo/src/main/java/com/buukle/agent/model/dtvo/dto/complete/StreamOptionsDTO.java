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
    /** 流式响应末尾携带 usage（OpenAI / 兼容实现支持）。 */
    @JsonProperty("include_usage")
    private Boolean includeUsage;
}