package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.domain.vo.SuperAgentUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;

import java.util.List;

public interface AgentLlmInteractionRecordSpi {
    /** Persist one LLM call record and return the auto-increment id (for the Timeline to reference by turn). */
    Long createRecord(AgentLlmInteractionRecordVO vo);

    /**
     * Main Agent turn start: allocate an independent record for this call (V117).
     *
     * <p>When a RUNNING row already exists under the same {@code (runId, turnNo, attemptNo)} it is
     * reused (re-entry after a process interruption); otherwise a new {@code retryNo} is allocated
     * and inserted, ensuring the previous success/failure record is not overwritten.
     * attemptNo=0 is the primary route, 1..N are fallbacks.
     */
    Long createOrGetRunning(AgentLlmInteractionRecordVO vo);

    /** Main Agent turn end: update only records that are still RUNNING, so an old attempt does not overwrite a new one. */
    void complete(Long id, AgentLlmInteractionRecordVO vo);

    /** Get the attempt id currently to be displayed for (runId, turnNo) (prefer RUNNING, then the latest). */
    Long findByRunAndTurn(Long runId, Integer turnNo);

    /** Fallback: set records still RUNNING past the threshold to FAILED (zombie records left by a process interruption). */
    int failStaleRunning(int staleMinutes);

    List<AgentLlmInteractionRecordVO> listByRunId(Long runId, int offset, int limit);

    /** All LLM interactions of the current run (ascending id; used by resume to restore per-turn assistant bodies). */
    List<AgentLlmInteractionRecordVO> listByRunIdAsc(Long runId);

    long countByRunId(Long runId);

    AgentLlmInteractionRecordVO getById(Long id);

    /** Session-level usage aggregation (for the bottom-docked display in the chat area). */
    SessionUsageVO usageSummary(Long sessionId);

    /** Aggregate one Super-Agent usage by day and session type. */
    List<SuperAgentUsageVO> usageBySuperAgent(Long superAgentId, int days);

    /**
     * Backfill this run's latest CHAT_REPLY record whose {@code reply_content} is empty (finish's
     * summary becomes that turn's reply).
     *
     * <p>The finish answer lives in the tool_calls arguments, so the LLM turn's own reply is empty;
     * the timeline ASSISTANT row is rendered from interaction.reply_content and would stay blank
     * forever without this backfill.
     *
     * @return true means a record with an empty reply was actually updated
     */
    boolean updateLatestBlankReply(Long runId, String reply);
}
