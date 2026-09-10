package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;

/** 单个 run 的用量聚合（agent_llm_interaction_record 冗余标量列求和）。 */
@Data
public class RunUsageVO implements Serializable {
    private Long runId;
    /** 该 run 的 LLM 调用次数。 */
    private Long interactionCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long cacheHitTokens;
    private Long cacheMissTokens;

    /** 空摘要（无任何调用数据时）。 */
    public static RunUsageVO empty(Long runId) {
        RunUsageVO vo = new RunUsageVO();
        vo.setRunId(runId);
        vo.setInteractionCount(0L);
        vo.setPromptTokens(0L);
        vo.setCompletionTokens(0L);
        vo.setTotalTokens(0L);
        vo.setCacheHitTokens(0L);
        vo.setCacheMissTokens(0L);
        return vo;
    }
}