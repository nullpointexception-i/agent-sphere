package com.buukle.agent.instance.service;

import com.buukle.agent.instance.domain.vo.RunActivityListVO;
import com.buukle.agent.instance.domain.vo.RunActivityVO;

public interface RunActivityService {
    RunActivityListVO listByRun(Long runId, Long sessionId, int offset, int limit);

    /** 按需读取单条活动的完整报文（列表不返回大字段）。 */
    RunActivityVO getActivity(Long runId, Long sessionId, String activityType, Long id);
}
