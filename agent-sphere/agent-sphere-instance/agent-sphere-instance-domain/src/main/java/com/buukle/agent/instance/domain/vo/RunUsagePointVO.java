package com.buukle.agent.instance.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/** Token metrics of a single LLM interaction (excluding the large request/response fields). */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunUsagePointVO implements Serializable {
    private Long id;
    private String interactionType;
    private String modelName;
    private String createdAt;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
}
