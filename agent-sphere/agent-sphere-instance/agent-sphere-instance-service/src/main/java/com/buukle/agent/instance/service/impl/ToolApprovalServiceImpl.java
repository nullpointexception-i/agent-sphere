package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.instance.domain.AgentToolApproval;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.repository.AgentToolApprovalMapper;
import com.buukle.agent.instance.spi.ToolApprovalSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ToolApprovalServiceImpl implements ToolApprovalSpi {

    private final AgentToolApprovalMapper mapper;

    @Override
    public void createPending(String approvalId, Long sessionId, Long runId, String toolCallsJson) {
        AgentToolApproval row = new AgentToolApproval();
        row.setApprovalId(approvalId);
        row.setSessionId(sessionId);
        row.setRunId(runId);
        row.setToolCallsJson(toolCallsJson);
        row.setStatus("PENDING");
        mapper.insert(row);
    }

    @Override
    public ToolApprovalVO getPending(Long sessionId) {
        AgentToolApproval row = mapper.selectOne(new LambdaQueryWrapper<AgentToolApproval>()
                .eq(AgentToolApproval::getSessionId, sessionId)
                .eq(AgentToolApproval::getStatus, "PENDING")
                .orderByAsc(AgentToolApproval::getId)
                .last("LIMIT 1"));
        return toVO(row);
    }

    @Override
    public ToolApprovalVO getByApprovalId(String approvalId) {
        return toVO(mapper.selectOne(new LambdaQueryWrapper<AgentToolApproval>()
                .eq(AgentToolApproval::getApprovalId, approvalId)
                .last("LIMIT 1")));
    }

    @Override
    public List<ToolApprovalVO> listPendingByRunId(Long runId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentToolApproval>()
                        .eq(AgentToolApproval::getRunId, runId)
                        .eq(AgentToolApproval::getStatus, "PENDING")
                        .orderByAsc(AgentToolApproval::getId))
                .stream().map(ToolApprovalServiceImpl::toVO).toList();
    }

    @Override
    public ToolApprovalVO findResumableByRunId(Long runId) {
        AgentToolApproval row = mapper.selectOne(new LambdaQueryWrapper<AgentToolApproval>()
                .eq(AgentToolApproval::getRunId, runId)
                .eq(AgentToolApproval::getStatus, "APPROVED")
                .orderByDesc(AgentToolApproval::getId)
                .last("LIMIT 1"));
        return toVO(row);
    }

    @Override
    public void resolve(String approvalId, String decisionsJson, String status, String approvedBy) {
        mapper.update(null, new LambdaUpdateWrapper<AgentToolApproval>()
                .eq(AgentToolApproval::getApprovalId, approvalId)
                .set(AgentToolApproval::getDecisionsJson, decisionsJson)
                .set(AgentToolApproval::getStatus, status)
                .set(AgentToolApproval::getApprovedBy, approvedBy));
    }

    @Override
    public void consume(String approvalId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentToolApproval>()
                .eq(AgentToolApproval::getApprovalId, approvalId)
                .eq(AgentToolApproval::getStatus, "APPROVED")
                .set(AgentToolApproval::getStatus, "CONSUMED"));
    }

    @Override
    public void dismissByRunId(Long runId, String status) {
        mapper.update(null, new LambdaUpdateWrapper<AgentToolApproval>()
                .eq(AgentToolApproval::getRunId, runId)
                .eq(AgentToolApproval::getStatus, "PENDING")
                .set(AgentToolApproval::getStatus, status));
    }

    private static ToolApprovalVO toVO(AgentToolApproval row) {
        if (row == null) return null;
        ToolApprovalVO vo = new ToolApprovalVO();
        vo.setId(row.getId());
        vo.setApprovalId(row.getApprovalId());
        vo.setSessionId(row.getSessionId());
        vo.setRunId(row.getRunId());
        vo.setToolCallsJson(row.getToolCallsJson());
        vo.setDecisionsJson(row.getDecisionsJson());
        vo.setStatus(row.getStatus());
        return vo;
    }
}
