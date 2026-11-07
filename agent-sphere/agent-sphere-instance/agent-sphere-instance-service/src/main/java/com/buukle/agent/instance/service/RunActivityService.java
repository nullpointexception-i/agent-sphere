package com.buukle.agent.instance.service;

import com.buukle.agent.instance.domain.vo.RunActivityListVO;
import com.buukle.agent.instance.domain.vo.RunActivityVO;
import com.buukle.agent.instance.domain.vo.RunUsageSeriesVO;

public interface RunActivityService {
    RunActivityListVO listByRun(Long runId, Long sessionId, int offset, int limit);

    /** Read the full payload of a single activity on demand (the list does not return large fields). */
    RunActivityVO getActivity(Long runId, Long sessionId, String activityType, Long id);

    /** Token series of the most recent N LLM interactions (300 by default, with afterId incremental support). */
    RunUsageSeriesVO listUsageSeries(Long runId, Long sessionId, long afterId, int limit);
}
