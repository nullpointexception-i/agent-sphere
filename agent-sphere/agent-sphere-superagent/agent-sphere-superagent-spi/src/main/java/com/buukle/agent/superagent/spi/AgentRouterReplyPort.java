package com.buukle.agent.superagent.spi;

/**
 * Auto-persists the Router's final assistant reply as an outbound IM message when
 * the run finished with plain text instead of a {@code send_message} tool call.
 */
public interface AgentRouterReplyPort {

    /**
     * @param sessionId the Router session id
     * @param runId     the completed run id
     * @param threadId  the IM thread id (resolved from the session when null)
     * @param text      the final reply text; blank text is ignored
     */
    void sendFinalReply(Long sessionId, Long runId, Long threadId, String text);
}
