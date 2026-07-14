package com.buukle.agent.instance.service;

import com.buukle.agent.instance.domain.vo.RunActivityListVO;

public interface RunActivityService {
    RunActivityListVO listByRun(Long runId, Long sessionId, int offset, int limit);
}
