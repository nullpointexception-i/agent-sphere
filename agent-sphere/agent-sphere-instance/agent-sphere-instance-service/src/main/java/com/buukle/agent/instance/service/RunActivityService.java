package com.buukle.agent.instance.service;

import java.util.Map;

public interface RunActivityService {
    Map<String, Object> listByRun(Long runId, Long sessionId, int offset, int limit);
}
