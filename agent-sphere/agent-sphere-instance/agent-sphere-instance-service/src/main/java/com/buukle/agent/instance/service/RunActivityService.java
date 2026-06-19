package com.buukle.agent.instance.service;

import com.buukle.agent.instance.domain.vo.RunActivityVO;

import java.util.List;
import java.util.Map;

public interface RunActivityService {
    Map<String, Object> listByRun(Long runId, Long sessionId, int offset, int limit);
}
