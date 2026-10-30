package com.buukle.agent.instance.spi;

/** Session/run scoped L2 tool approvals. */
public interface ToolApprovalGrantSpi {
    boolean isGranted(Long sessionId, Long runId, String toolRef, String scope);

    boolean isDenied(Long sessionId, Long runId, String toolRef);

    void grant(Long sessionId, Long runId, String toolRef, String displayName,
               String securityLevel, String scope, String approvedBy);

    java.util.List<com.buukle.agent.instance.dtvo.vo.ToolApprovalGrantVO> listApprovedBySession(Long sessionId);

    void deny(Long sessionId, Long runId, String toolRef, String approvedBy);
}
