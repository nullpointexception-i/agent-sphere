package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;

public interface AgentCompactRecordSpi {
    AgentCompactRecordVO createRecord(Long sessionId);

    void updateCompleted(Long id, String summaryBefore, String summaryAfter, Long tokenCount, Long compactedUptoRunId);

    void updateFailed(Long id, String errorMessage);

    AgentCompactRecordVO getLatestBySessionId(Long sessionId);

    AgentCompactRecordVO getLatestCompleted(Long sessionId);
}
