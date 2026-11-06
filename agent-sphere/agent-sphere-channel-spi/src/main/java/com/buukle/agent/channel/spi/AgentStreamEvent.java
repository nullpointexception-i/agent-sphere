package com.buukle.agent.channel.spi;

/**
 * One ephemeral Agent streaming fragment pushed over an existing channel
 * (for example the user-scoped IM SSE stream).
 *
 * <p>Stream events are display-only: they are never persisted and must never be
 * treated as the authoritative reply. The authoritative message arrives through
 * the normal outbound delivery path.
 */
public record AgentStreamEvent(
        Long threadId,
        Long sessionId,
        Long runId,
        Long agentId,
        /** thinking / reply / status (see {@link AgentStreamPhase}). */
        String phase,
        /** Incremental text fragment for thinking / reply; always empty for status. */
        String delta,
        /** started / completed / failed / cancelled (see {@link AgentStreamStatus}); null on deltas. */
        String streamStatus,
        String toolName,
        String displayName,
        String errorMessage,
        /** Monotonic per (runId, phase) fragment counter for de-duplication. */
        Long streamSeq,
        Long timestamp
) {
}
