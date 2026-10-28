package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;

public interface AgentCompactRecordSpi {
    AgentCompactRecordVO createRecord(Long sessionId);

    /** 创建 run-local 压缩记录（run_id 非空，不参与 session 游标）。 */
    AgentCompactRecordVO createRunRecord(Long sessionId, Long runId);

    /** 压缩调用开始（PENDING → RUNNING）；失败/崩溃的 RUNNING 可被对账任务收口。 */
    void updateRunning(Long id);

    void updateCompleted(Long id, String summaryBefore, String summaryAfter, Long tokenCount, Long compactedUptoRunId);

    /** 完成 run-local 压缩：摘要 + 未摘要尾部（tail_json），不动 session 游标。 */
    void completeRunCompact(Long id, String summaryBefore, String summaryAfter, Long tokenCount, String tailJson);

    /**
     * wait/澄清挂起时更新当前 run 的未摘要尾部快照。
     * 只更新最近一条 COMPLETED run-local 记录的 tail_json；没有记录时 no-op。
     */
    void updateRunTail(Long runId, String tailJson);

    /** 把超过阈值的 PENDING/RUNNING 记录置 FAILED（进程崩溃/重启遗留），返回影响行数。 */
    int failStalePending(java.time.LocalDateTime olderThan);

    void updateFailed(Long id, String errorMessage);

    AgentCompactRecordVO getLatestBySessionId(Long sessionId);

    /** 最近完成的 session 级压缩（run_id IS NULL）。 */
    AgentCompactRecordVO getLatestCompleted(Long sessionId);

    /** 当前 run 最近完成的 run-local 压缩（resume 恢复 summary + tail 用）。 */
    AgentCompactRecordVO getLatestCompletedByRunId(Long runId);
}
