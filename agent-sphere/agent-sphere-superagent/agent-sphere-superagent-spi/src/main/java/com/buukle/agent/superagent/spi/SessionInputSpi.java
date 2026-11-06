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
