package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;

/** 单个会话的用量聚合（跨所有 run 的 LLM 调用求和，会话级展示用）。 */
@Data
public class SessionUsageVO implements Serializable {
    private Long sessionId;
    /** 覆盖的 run 数。 */
    private Long runCount;
    /** 会话内 LLM 调用总次数。 */
    private Long interactionCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long cacheHitTokens;
    private Long cacheMissTokens;
    /** 缓存命中率（0-100，无用量时为 null）。 */
    private Double cacheHitRate;
}