package com.buukle.agent.instance.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.MessageHistoryVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface RunSpi {
    RunVO createRun(CreateRunDTO dto);

    RunVO getRun(Long id);

    /** Lightweight read: does not load the large reasoning field (used by scanning tasks). */
    RunVO getRunMeta(Long id);

    /** The session's current latest active run (status ∈ PENDING/RUNNING/AWAITING_USER); null when none. */
    RunVO findActiveRun(Long sessionId);

    void updateRun(RunVO run);

    /** Reset the backoff counter after a successful turn (only retry_attempt/wait_kind are cleared; other columns are untouched). */
    boolean resetRetryState(Long runId);

    /** Conditional transition to RUNNING: terminal/cancelled runs are not pulled back by a late wake-up. */
    boolean markRunning(Long runId, int loopCount, int loopLimit);

    /** Conditionally suspend for LLM_RETRY: only RUNNING/WAITING may be written, so a cancellation is not overwritten. */
    boolean markWaitingForRetry(Long runId, int attempt);

    /** Roll back to WAITING (only allowed from RUNNING). */
    boolean markWaiting(Long runId);

    /** CAS resume: succeeds only for WAITING -> RUNNING and returns the updated run (null otherwise). */
    RunVO claimForResume(Long runId);

    /** Update the status only when the run is not terminal (for PENDING/RUNNING/WAITING/AWAITING events). */
    boolean updateStatusIfActive(Long runId, String status);

    /** Set CANCELLED only when the run is not terminal (the database decides when cancellation races a suspend). */
    boolean cancelIfActive(Long runId);

    List<RunVO> listRunsBySessionAfterId(Long sessionId, Long afterRunId);

    IPage<RunVO> pageRunsBySession(Long sessionId, String keyword, int page, int size);

    /** Batch-fetch reasoning on demand: runId → persisted reasoning. Queries only the reasoning column, for targeted fill of off-row lists. */
    Map<Long, String> findReasoningBatch(Collection<Long> runIds);

    MessageHistoryVO getMessageHistory(Long sessionId, String direction, Long runId);

    /** For reconciliation: find runs by status + updated_at earlier than a point in time (ascending, at most limit rows). */
    List<RunVO> findStaleByStatus(String status, LocalDateTime olderThan, int limit);

    List<Long> findStaleRunIdsByStatus(String status, LocalDateTime olderThan, int limit);

    boolean existsRunAfterId(Long sessionId, Long runId);
}
