package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;

/** Daily LLM usage of one Super-Agent, split by session type. */
@Data
public class SuperAgentUsageVO implements Serializable {
    private Long superAgentId;
    /** Calendar day in {@code yyyy-MM-dd}. */
    private String day;
    /** Session type: ROUTER / WORKER / CHAT / GROUP_ROUTER. */
    private String sessionType;
    private Long runCount;
    private Long interactionCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long cacheHitTokens;
    private Long cacheMissTokens;
}
