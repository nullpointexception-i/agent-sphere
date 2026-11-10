package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.buukle.agent.common.context.AgentContextSnapshot;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.RouterInputResumePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resumes a parked Router run from the IM channel by acting as the session owner.
 *
 * <p>Mirrors {@link AgentRouterRunService}: background threads run without tenant/auth
 * context, then the Router session owner is applied so
 * {@code ChatRuntimeService#assertSessionOwnership} passes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterInputResumeService implements RouterInputResumePort {

    private final SessionSpi sessionSpi;
    private final ChatRuntimeService chatRuntimeService;

    @Override
    public void answerClarification(Long sessionId, Long runId, String clarificationId, String response) {
        withOwnerContext(sessionId, () ->
                chatRuntimeService.resumeFromClarification(sessionId, runId, response, clarificationId));
    }

    @Override
    public void resolveApproval(Long sessionId, Long runId, String approvalId, String decisionsJson) {
        resolveApproval(sessionId, runId, approvalId, decisionsJson, null);
    }

    @Override
    public void resolveApproval(Long sessionId, Long runId, String approvalId,
                                String decisionsJson, String approvedBy) {
        withOwnerContext(sessionId, () -> chatRuntimeService.resolveToolApproval(
                sessionId, runId, approvalId, decisionsJson, approvedBy));
    }

    private void withOwnerContext(Long sessionId, Runnable action) {
        AgentContextSnapshot caller = AgentContextSnapshot.capture();
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session == null) {
                return;
            }
            // ROUTER sessions are internal: the resume must look like a runtime call, not a
            // console request, otherwise SessionAccessPolicy rejects it as read-only.
            SessionSurfaceHolder.set(SessionSurface.INTERNAL);
            AuthContext.setSuperAdmin(true);
            if (session.getCreatedBy() != null) {
                AuthContext.setUsername(session.getCreatedBy());
            }
            action.run();
        } finally {
            caller.restore();
        }
    }
}
