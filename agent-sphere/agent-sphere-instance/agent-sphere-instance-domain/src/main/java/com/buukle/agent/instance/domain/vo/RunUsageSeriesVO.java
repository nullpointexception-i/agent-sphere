package com.buukle.agent.instance.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 最近 N 条主 Agent LLM 交互的 token 序列。 */
@Data
@AllArgsConstructor
public class RunUsageSeriesVO implements Serializable {
    private List<RunUsagePointVO> points;
    /** 是否还有比当前窗口更早的交互（仅首次加载有意义）。 */
    private boolean hasMore;
}
