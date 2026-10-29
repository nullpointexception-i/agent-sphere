package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ToolApprovalScope;
import com.buukle.agent.instance.domain.AgentToolApprovalGrant;
import com.buukle.agent.instance.repository.AgentToolApprovalGrantMapper;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ToolApprovalGrantServiceImpl implements ToolApprovalGrantSpi {

    private final AgentToolApprovalGrantMapper mapper;

    @Override
    public boolean isGranted(Long sessionId, Long runId, String toolRef, String scope) {
        ToolApprovalScope maxScope = ToolApprovalScope.from(scope);
        if (toolRef == null || toolRef.isBlank() || maxScope == ToolApprovalScope.ONCE) {
            return false;
        }
        LambdaQueryWrapper<AgentToolApprovalGrant> query = new LambdaQueryWrapper<AgentToolApprovalGrant>()
                .eq(AgentToolApprovalGrant::getToolRef, toolRef)
                .eq(AgentToolApprovalGrant::getStatus, "APPROVED");
        if (maxScope == ToolApprovalScope.SESSION) {
            query.and(w -> {
                w.nested(x -> x.eq(AgentToolApprovalGrant::getScope, ToolApprovalScope.SESSION.name())
                        .eq(AgentToolApprovalGrant::getSessionId, sessionId));
                if (runId != null) {
                    w.or().nested(x -> x.eq(AgentToolApprovalGrant::getScope, ToolApprovalScope.RUN.name())
                            .eq(AgentToolApprovalGrant::getRunId, runId));
                }
            });
        } else {
            if (runId == null) {
                return false;
            }
            query.eq(AgentToolApprovalGrant::getScope, ToolApprovalScope.RUN.name())
                    .eq(AgentToolApprovalGrant::getRunId, runId);
        }
        Long count = mapper.selectCount(query);
        return count != null && count > 0;
    }

    @Override
    public boolean isDenied(Long sessionId, Long runId, String toolRef) {
        if (toolRef == null || toolRef.isBlank() || runId == null) {
            return false;
        }
        Long count = mapper.selectCount(new LambdaQueryWrapper<AgentToolApprovalGrant>()
                .eq(AgentToolApprovalGrant::getToolRef, toolRef)
                .eq(AgentToolApprovalGrant::getStatus, "DENIED")
                .eq(AgentToolApprovalGrant::getScope, ToolApprovalScope.RUN.name())
                .eq(AgentToolApprovalGrant::getRunId, runId));
        return count != null && count > 0;
    }

    @Override
    public void deny(Long sessionId, Long runId, String toolRef, String approvedBy) {
        if (toolRef == null || toolRef.isBlank()) return;
        AgentToolApprovalGrant existing = mapper.selectOne(new LambdaQueryWrapper<AgentToolApprovalGrant>()
                .eq(AgentToolApprovalGrant::getToolRef, toolRef)
                .eq(AgentToolApprovalGrant::getScope, ToolApprovalScope.RUN.name())
                .eq(AgentToolApprovalGrant::getRunId, runId)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setStatus("DENIED");
            existing.setApprovedBy(approvedBy);
            mapper.updateById(existing);
            return;
        }
        AgentToolApprovalGrant grant = new AgentToolApprovalGrant();
        grant.setSessionId(sessionId);
        grant.setRunId(runId);
        grant.setToolRef(toolRef);
        grant.setScope(ToolApprovalScope.RUN.name());
        grant.setStatus("DENIED");
        grant.setApprovedBy(approvedBy);
        mapper.insert(grant);
    }

    @Override
    public void grant(Long sessionId, Long runId, String toolRef, String scope, String approvedBy) {
        ToolApprovalScope approvalScope = ToolApprovalScope.from(scope);
        if (approvalScope == ToolApprovalScope.ONCE || toolRef == null || toolRef.isBlank()) {
            return;
        }
        LambdaQueryWrapper<AgentToolApprovalGrant> existingQuery = new LambdaQueryWrapper<AgentToolApprovalGrant>()
                .eq(AgentToolApprovalGrant::getToolRef, toolRef)
                .eq(AgentToolApprovalGrant::getScope, approvalScope.name());
        if (approvalScope == ToolApprovalScope.SESSION) {
            existingQuery.eq(AgentToolApprovalGrant::getSessionId, sessionId);
        } else {
            existingQuery.eq(AgentToolApprovalGrant::getRunId, runId);
        }
        AgentToolApprovalGrant existing = mapper.selectOne(existingQuery.last("LIMIT 1"));
        if (existing != null) {
            existing.setStatus("APPROVED");
            existing.setApprovedBy(approvedBy);
            mapper.updateById(existing);
            return;
        }
        AgentToolApprovalGrant grant = new AgentToolApprovalGrant();
        grant.setSessionId(sessionId);
        grant.setRunId(approvalScope == ToolApprovalScope.RUN ? runId : null);
        grant.setToolRef(toolRef);
        grant.setScope(approvalScope.name());
        grant.setApprovedBy(approvedBy);
        grant.setStatus("APPROVED");
        mapper.insert(grant);
    }
}
