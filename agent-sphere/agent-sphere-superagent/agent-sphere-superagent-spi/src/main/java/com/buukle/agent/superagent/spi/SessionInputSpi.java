package com.buukle.agent.superagent.spi;

/** Bridge between Router parked runs (clarification / tool approval) and the IM DM. */
public interface SessionInputSpi {

    /** Projects a pending input and enqueues the DM card. Idempotent per (type, inputId). */
    void projectPending(SessionInputCommand command);

    /** Marks an input as ANSWERED/CANCELLED and updates its card in place. */
    void projectResolved(SessionInputResolveCommand command);

    /** Cancels every pending input of a terminal run (card cleanup). */
    void projectRunTerminal(Long runId);

    /** Returns the single pending input of a thread for {@code inputType}, or null when none/ambiguous. */
    com.buukle.agent.superagent.dtvo.vo.SessionInputVO findPendingByThread(Long threadId, String inputType);

    /** Returns a specific pending input by its business id, or null when it is gone/resolved. */
    com.buukle.agent.superagent.dtvo.vo.SessionInputVO findPendingByInput(Long threadId, String inputId);

    /** Returns a specific pending tool approval by its business id, or null when it is gone/resolved. */
    com.buukle.agent.superagent.dtvo.vo.SessionInputVO findPendingApprovalByInput(
            Long threadId, String inputId);

    /** Returns the pending clarification of one avatar in a thread, or null when none. */
    com.buukle.agent.superagent.dtvo.vo.SessionInputVO findPendingByThreadAndAgent(
            Long threadId, Long superAgentId);

    /** Returns every pending clarification of a thread (used to detect ambiguity). */
    java.util.List<com.buukle.agent.superagent.dtvo.vo.SessionInputVO> findPendingClarifications(
            Long threadId);

    /**
     * Answers one specific clarification and resumes the parked Router run.
     *
     * @param responder username recorded on the input for audit
     * @return true when this call resolved the input; false when it was already gone/resolved
     */
    boolean answerRouterClarification(
            Long threadId, String inputId, String response, String responder);

    /**
     * Consumes the user's plain-text reply as the answer to the thread's pending
     * clarification and resumes the parked Router run.
     *
     * @return true when the text was consumed (caller must not start a new Router run)
     */
    boolean answerRouterClarification(Long threadId, String response);

    /** Resolves the thread's pending tool approval with the user's per-call decisions. */
    void resolveRouterApproval(Long threadId, String inputId, String decisionsJson);
}
