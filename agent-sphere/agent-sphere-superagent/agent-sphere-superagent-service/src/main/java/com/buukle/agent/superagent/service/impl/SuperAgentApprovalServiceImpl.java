package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.dtvo.dto.ResolveApprovalDTO;
import com.buukle.agent.superagent.dtvo.vo.PendingApprovalVO;
import com.buukle.agent.superagent.repository.PendingApprovalRow;
import com.buukle.agent.superagent.repository.SuperAgentApprovalMapper;
import com.buukle.agent.superagent.service.SuperAgentApprovalService;
import com.buukle.agent.superagent.spi.RouterInputResumePort;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentApprovalServiceImpl implements SuperAgentApprovalService {

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STATUS_PENDING = "PENDING";

    private final SuperAgentApprovalMapper approvalMapper;
    private final ObjectProvider<RouterInputResumePort> resumePortProvider;

    @Override
    public List<PendingApprovalVO> listPending() {
        Long userId = currentUserId();
        List<PendingApprovalRow> rows = AuthContext.isSuperAdmin()
                ? approvalMapper.listPendingAll()
                : approvalMapper.listPendingForOwner(userId, AuthContext.getUsername());
        List<PendingApprovalVO> result = new ArrayList<>(rows.size());
        for (PendingApprovalRow row : rows) {
            PendingApprovalVO vo = new PendingApprovalVO();
            vo.setApprovalId(row.getApprovalId());
            vo.setSessionId(row.getSessionId());
            vo.setRunId(row.getRunId());
            vo.setSessionType(row.getSessionType());
            vo.setSuperAgentId(row.getSuperAgentId());
            vo.setAgentName(row.getAgentName());
            vo.setWorkItemId(row.getWorkItemId());
            vo.setSessionTitle(row.getSessionTitle());
            vo.setToolCallsJson(row.getToolCallsJson());
            vo.setCreatedAt(row.getCreatedAt() == null ? null
                    : row.getCreatedAt().format(DTF));
            result.add(vo);
        }
        return result;
    }

    @Override
    public void resolve(String approvalId, ResolveApprovalDTO dto) {
        if (approvalId == null || approvalId.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "approvalId is required");
        }
        PendingApprovalRow row = approvalMapper.findByApprovalId(approvalId);
        if (row == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "approval not found");
        }
        if (!STATUS_PENDING.equals(row.getStatus())) {
            // Another tab/device already handled it: keep the inbox idempotent.
            return;
        }
        Long userId = currentUserId();
        if (!AuthContext.isSuperAdmin() && !Objects.equals(userId, row.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        RouterInputResumePort port = resumePortProvider.getIfAvailable();
        if (port == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "runtime resume port is unavailable");
        }
        String decisionsJson = JsonUtils.toJson(dto.getDecisions());
        String approver = AuthContext.getUsername();
        port.resolveApproval(row.getSessionId(), row.getRunId(), approvalId,
                decisionsJson, approver);
        log.info("Super-agent approval resolved: approval={}, session={}, run={}, by={}",
                approvalId, row.getSessionId(), row.getRunId(), approver);
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
