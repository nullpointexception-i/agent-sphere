package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;

/** Usage aggregation for a single run (sum of the redundant scalar columns in agent_llm_interaction_record). */
@Data
public class RunUsageVO implements Serializable {
    private Long runId;
    /** Number of LLM calls in this run. */
    private Long interactionCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long cacheHitTokens;
    private Long cacheMissTokens;

    /** Empty summary (when there is no call data at all). */
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