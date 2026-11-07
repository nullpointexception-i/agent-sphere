package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;

public interface AgentCompactRecordSpi {
    AgentCompactRecordVO createRecord(Long sessionId);

    /** Create a run-local compaction record (run_id non-null; does not participate in the session cursor). */
    AgentCompactRecordVO createRunRecord(Long sessionId, Long runId);

    /** Compaction call started (PENDING → RUNNING); a failed/crashed RUNNING record can be finalized by the reconciliation task. */
    void updateRunning(Long id);

    void updateCompleted(Long id, String summaryBefore, String summaryAfter, Long tokenCount, Long compactedUptoRunId);

    /** Complete run-local compaction: summary + un-summarized tail (tail_json); the session cursor is left untouched. */
    void completeRunCompact(Long id, String summaryBefore, String summaryAfter, Long tokenCount, String tailJson);

    /**
     * Update the un-summarized tail snapshot of the current run while a wait/clarification is suspended.
     * Only the tail_json of the most recent COMPLETED run-local record is updated; no-op when there is none.
     */
    void updateRunTail(Long runId, String tailJson);

    /** Set PENDING/RUNNING records older than the threshold to FAILED (left over from a crash/restart); returns the affected row count. */
    int failStalePending(java.time.LocalDateTime olderThan);

    void updateFailed(Long id, String errorMessage);

    AgentCompactRecordVO getLatestBySessionId(Long sessionId);

    /** Most recent completed session-level compaction (run_id IS NULL). */
    AgentCompactRecordVO getLatestCompleted(Long sessionId);

    /** Most recent completed run-local compaction of the current run (used by resume to restore summary + tail). */
    AgentCompactRecordVO getLatestCompletedByRunId(Long runId);

    /** Number of run-local compactions already completed for the current run (for the per-run cap). */
    long countCompletedByRunId(Long runId);
}
