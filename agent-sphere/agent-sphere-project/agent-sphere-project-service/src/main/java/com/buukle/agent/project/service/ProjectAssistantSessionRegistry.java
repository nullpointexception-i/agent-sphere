package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.project.domain.PmProjectAssistantSession;
import com.buukle.agent.project.repository.PmProjectAssistantSessionMapper;
import com.buukle.agent.project.spi.ProjectAssistantSessionInfo;
import com.buukle.agent.project.spi.ProjectAssistantSessionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/** Registry lookup that tells a PROJECT_ROUTER session which project and requester it serves. */
@Service
@RequiredArgsConstructor
public class ProjectAssistantSessionRegistry implements ProjectAssistantSessionPort {

    private final PmProjectAssistantSessionMapper sessionMapper;

    public void register(Long tenantId, Long projectId, Long sessionId, Long canonicalSessionId,
                         Long requesterUserId, String sessionRole) {
        if (sessionId == null || projectId == null) {
            return;
        }
        PmProjectAssistantSession existing = findBySessionId(sessionId);
        if (existing == null) {
            PmProjectAssistantSession row = new PmProjectAssistantSession();
            row.setTenantId(tenantId);
            row.setProjectId(projectId);
            row.setSessionId(sessionId);
            row.setCanonicalSessionId(canonicalSessionId);
            row.setRequesterUserId(requesterUserId);
            row.setSessionRole(sessionRole);
            row.setStatus(ProjectAssistantConstants.STATUS_ACTIVE);
            sessionMapper.insert(row);
            return;
        }
        boolean changed = !java.util.Objects.equals(existing.getProjectId(), projectId)
                || !java.util.Objects.equals(existing.getCanonicalSessionId(), canonicalSessionId)
                || !java.util.Objects.equals(existing.getRequesterUserId(), requesterUserId)
                || !sessionRole.equals(existing.getSessionRole());
        if (changed) {
            existing.setProjectId(projectId);
            existing.setCanonicalSessionId(canonicalSessionId);
            existing.setRequesterUserId(requesterUserId);
            existing.setSessionRole(sessionRole);
            sessionMapper.updateById(existing);
        }
    }

    public PmProjectAssistantSession findBySessionId(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return withoutScope(() -> sessionMapper.selectOne(
                new LambdaQueryWrapper<PmProjectAssistantSession>()
                        .eq(PmProjectAssistantSession::getSessionId, sessionId)
                        .last("LIMIT 1")));
    }

    public PmProjectAssistantSession findCanonical(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return withoutScope(() -> sessionMapper.selectOne(
                new LambdaQueryWrapper<PmProjectAssistantSession>()
                        .eq(PmProjectAssistantSession::getProjectId, projectId)
                        .eq(PmProjectAssistantSession::getSessionRole,
                                PmProjectAssistantSession.ROLE_CANONICAL)
                        .orderByAsc(PmProjectAssistantSession::getId)
                        .last("LIMIT 1")));
    }

    @Override
    public ProjectAssistantSessionInfo findInfoBySessionId(Long sessionId) {
        PmProjectAssistantSession row = findBySessionId(sessionId);
        if (row == null) {
            return null;
        }
        return new ProjectAssistantSessionInfo(row.getTenantId(), row.getProjectId(),
                row.getCanonicalSessionId(), row.getRequesterUserId(), row.getSessionRole());
    }

    /** The mapping table is session-keyed; clear the request scope so a fork can be resolved. */
    private <T> T withoutScope(Supplier<T> supplier) {
        TenantScope previous = TenantScopeHolder.get();
        try {
            TenantScopeHolder.clear();
            return supplier.get();
        } finally {
            if (previous == null) {
                TenantScopeHolder.clear();
            } else {
                TenantScopeHolder.start(previous);
            }
        }
    }
}
