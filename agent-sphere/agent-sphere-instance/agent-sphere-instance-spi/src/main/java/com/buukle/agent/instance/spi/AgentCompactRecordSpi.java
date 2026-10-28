package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;

public interface AgentCompactRecordSpi {
    AgentCompactRecordVO createRecord(Long sessionId);

    /** 压缩调用开始（PENDING → RUNNING）；失败/崩溃的 RUNNING 可被对账任务收口。 */
    void updateRunning(Long id);

    void updateCompleted(Long id, String summaryBefore, String summaryAfter, Long tokenCount, Long compactedUptoRunId);

    /** 把超过阈值的 PENDING/RUNNING 记录置 FAILED（进程崩溃/重启遗留），返回影响行数。 */
    int failStalePending(java.time.LocalDateTime olderThan);

    void updateFailed(Long id, String errorMessage);

    AgentCompactRecordVO getLatestBySessionId(Long sessionId);

    AgentCompactRecordVO getLatestCompleted(Long sessionId);
}
