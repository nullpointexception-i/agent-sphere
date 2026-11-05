package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ToolApprovalScope;
import com.buukle.agent.instance.domain.AgentToolApprovalGrant;
import com.buukle.agent.instance.dtvo.enums.ToolApprovalState;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalGrantVO;
import com.buukle.agent.instance.repository.AgentToolApprovalGrantMapper;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
                .eq(AgentToolApprovalGrant::getStatus, ToolApprovalState.APPROVED);
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
                .eq(AgentToolApprovalGrant::getStatus, ToolApprovalState.DENIED)
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
            existing.setStatus(ToolApprovalState.DENIED);
            existing.setApprovedBy(approvedBy);
            mapper.updateById(existing);
            return;
        }
        AgentToolApprovalGrant grant = new AgentToolApprovalGrant();
        grant.setSessionId(sessionId);
        grant.setRunId(runId);
        grant.setToolRef(toolRef);
        grant.setScope(ToolApprovalScope.RUN.name());
        grant.setStatus(ToolApprovalState.DENIED);
        grant.setApprovedBy(approvedBy);
        mapper.insert(grant);
    }

    @Override
    public void grant(Long sessionId, Long runId, String toolRef, String displayName,
                      String securityLevel, String scope, String approvedBy) {
        ToolApprovalScope approvalScope = ToolApprovalScope.from(scope);
        if (approvalScope == ToolApprovalScope.ONCE || toolRef == null || toolRef.isBlank()) {
            return;
        }
        String resolvedName = displayName == null || displayName.isBlank() ? toolRef : displayName;
        String resolvedLevel = securityLevel == null || securityLevel.isBlank() ? "L2" : securityLevel;
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
            existing.setStatus(ToolApprovalState.APPROVED);
            existing.setApprovedBy(approvedBy);
            existing.setDisplayName(resolvedName);
            existing.setSecurityLevel(resolvedLevel);
            mapper.updateById(existing);
            return;
        }
        AgentToolApprovalGrant grant = new AgentToolApprovalGrant();
        grant.setSessionId(sessionId);
        grant.setRunId(approvalScope == ToolApprovalScope.RUN ? runId : null);
        grant.setToolRef(toolRef);
        grant.setDisplayName(resolvedName);
        grant.setSecurityLevel(resolvedLevel);
        grant.setScope(approvalScope.name());
        grant.setApprovedBy(approvedBy);
        grant.setStatus(ToolApprovalState.APPROVED);
        mapper.insert(grant);
    }

    @Override
    public List<ToolApprovalGrantVO> listApprovedBySession(Long sessionId) {
        if (sessionId == null) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<AgentToolApprovalGrant>()
                        .eq(AgentToolApprovalGrant::getSessionId, sessionId)
                        .eq(AgentToolApprovalGrant::getStatus, ToolApprovalState.APPROVED)
                        .orderByDesc(AgentToolApprovalGrant::getId))
                .stream().map(g -> {
                    ToolApprovalGrantVO vo = new ToolApprovalGrantVO();
                    vo.setToolRef(g.getToolRef());
                    vo.setDisplayName(g.getDisplayName() != null && !g.getDisplayName().isBlank()
                            ? g.getDisplayName() : g.getToolRef());
                    vo.setSecurityLevel(g.getSecurityLevel() != null && !g.getSecurityLevel().isBlank()
                            ? g.getSecurityLevel() : "L2");
                    vo.setScope(g.getScope());
                    return vo;
                }).toList();
    }
}
