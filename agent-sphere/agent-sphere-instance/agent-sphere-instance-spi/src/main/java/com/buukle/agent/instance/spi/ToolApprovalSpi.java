package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;

import java.util.List;

public interface ToolApprovalSpi {
    void createPending(String approvalId, Long sessionId, Long runId, String toolCallsJson);

    ToolApprovalVO getPending(Long sessionId);

    ToolApprovalVO getByApprovalId(String approvalId);

    List<ToolApprovalVO> listPendingByRunId(Long runId);

    ToolApprovalVO findResumableByRunId(Long runId);

    void resolve(String approvalId, String decisionsJson, String status, String approvedBy);

    void consume(String approvalId);

    void dismissByRunId(Long runId, String status);
}
