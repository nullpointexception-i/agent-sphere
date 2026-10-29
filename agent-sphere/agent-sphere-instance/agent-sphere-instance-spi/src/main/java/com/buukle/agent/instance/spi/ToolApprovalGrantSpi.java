package com.buukle.agent.instance.spi;

/** Session/run scoped L2 tool approvals. */
public interface ToolApprovalGrantSpi {
    boolean isGranted(Long sessionId, Long runId, String toolRef, String scope);

    boolean isDenied(Long sessionId, Long runId, String toolRef);

    void grant(Long sessionId, Long runId, String toolRef, String scope, String approvedBy);

    void deny(Long sessionId, Long runId, String toolRef, String approvedBy);
}
