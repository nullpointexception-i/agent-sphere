package com.buukle.agent.instance.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Token series of the most recent N LLM interactions. */
@Data
@AllArgsConstructor
public class RunUsageSeriesVO implements Serializable {
    private List<RunUsagePointVO> points;
    /** Whether there are interactions older than the current window (meaningful only on first load). */
    private boolean hasMore;
}
