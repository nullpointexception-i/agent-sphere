package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.TimelineRefQuery;

/**
 * Unified Timeline (flattened display index) contract:
 * session-level seq allocation, row recording/closing, keyset paged reads (body resolution).
 */
public interface AgentTimelineSpi {

    /** Allocate a session-level monotonic seq (Redis INCR, never reset). */
    long nextSeq(Long sessionId);

    /**
     * Record one timeline row (returns the allocated seq).
     */
    long record(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                Long refRunId, Long refInteractionId, Long refToolCallId,
                Long refSubAgentRunId, Long refClarificationId);

    /** Record a tool approval row (refApprovalId points to agent_tool_approval.id). */
    long recordApproval(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                        Long refRunId, Long refApprovalId);

    /**
     * Update a row's state/title (idempotent; skipped when state is empty).
     */
    void update(Long sessionId, Long seq, String state, String subtype, String title);

    /** Backfill the LLM interaction record id referenced by a row (the recorder fills it in after each turn's LLM call is persisted; one row per turn). */
    void updateRefInteractionId(Long sessionId, Long seq, Long interactionId);

    /** Backfill the compaction record id of a COMPACTION row (body resolved from agent_compact_record.summary_after). */
    void updateRefCompactId(Long sessionId, Long seq, Long compactRecordId);

    /**
     * Find the seq of the most recent row by run + kind + ref; returns null when absent.
     *
     * <p>Used by TimelineRecorder as a DB fallback when the in-process Map misses: across
     * replicas/restarts it still converges PENDING→ANSWERED and RUNNING→COMPLETED onto the same row.
     */
    Long findLatestSeq(TimelineRefQuery query);

    /** Find the state of the most recent row by run + kind + ref; returns null when absent. */
    String findLatestState(TimelineRefQuery query);

    /** Update the header row state for a sub-agent run id (closes it when sub_agent_run ends). */
    void updateBySubAgentRun(Long subAgentRunId, String state);

    /**
     * Create or reuse the assistant row for an LLM interaction record.
     *
     * <p>(ref_run_id, ref_interaction_id) is unique: after a restart/across replicas the same turn is
     * reused directly instead of creating a second empty bubble.
     */
    long recordAssistantByInteraction(Long sessionId, Long runId, Long interactionId);

    /** Close all assistant rows of this run that are still RUNNING (terminal-state fallback). */
    int closeRunningAssistant(Long sessionId, Long runId, String state);

    /** Find the seq of the assistant row by ref_interaction_id; returns null when absent. */
    Long findSeqByInteraction(Long runId, Long interactionId);

    /**
     * Keyset paging read: beforeSeq=scroll back (older), afterSeq=reconnect catch-up (newer), both empty=latest page;
     * returns the page (with bodies already resolved from ref).
     */
    com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO page(Long sessionId, Long beforeSeq, Long afterSeq, int limit);
}