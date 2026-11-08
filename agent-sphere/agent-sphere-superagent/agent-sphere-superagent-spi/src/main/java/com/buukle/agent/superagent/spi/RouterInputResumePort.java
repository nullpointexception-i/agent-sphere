package com.buukle.agent.superagent.spi;

/**
 * Resumes a parked Router run from the IM side. Implemented in
 * runtime-orchestration (needs {@code ChatRuntimeService}); consumers inject the
 * interface so the dependency direction stays superagent-service &rarr; superagent-spi.
 */
public interface RouterInputResumePort {

    /** Answers a clarification: mirrors the console {@code POST /runtime/{sid}/run/{runId}/clarify}. */
    void answerClarification(Long sessionId, Long runId, String clarificationId, String response);

    /** Resolves an L2 tool approval per call: mirrors the console approval endpoint. */
    void resolveApproval(Long sessionId, Long runId, String approvalId, String decisionsJson);

    /**
     * Same as {@link #resolveApproval(Long, Long, String, String)} but records
     * the real approver (workspace inbox/group owner path).
     */
    default void resolveApproval(Long sessionId, Long runId, String approvalId,
                                 String decisionsJson, String approvedBy) {
        resolveApproval(sessionId, runId, approvalId, decisionsJson);
    }
}
