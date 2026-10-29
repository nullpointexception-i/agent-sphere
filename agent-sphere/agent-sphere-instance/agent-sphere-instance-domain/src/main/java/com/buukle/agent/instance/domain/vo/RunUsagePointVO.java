package com.buukle.agent.instance.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/** 单次 LLM 交互的 token 指标（不含 request/response 大字段）。 */
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
