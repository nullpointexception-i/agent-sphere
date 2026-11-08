package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.dto.ResolveApprovalDTO;
import com.buukle.agent.superagent.dtvo.vo.PendingApprovalVO;

import java.util.List;

public interface SuperAgentApprovalService {

    /** Pending L2 approvals owned by the current user (all for super admins). */
    List<PendingApprovalVO> listPending();

    /** Resolve one approval as the current user; idempotent when already closed. */
    void resolve(String approvalId, ResolveApprovalDTO dto);
}
