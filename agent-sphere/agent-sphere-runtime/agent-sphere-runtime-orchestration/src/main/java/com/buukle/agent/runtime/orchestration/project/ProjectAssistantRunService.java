package com.buukle.agent.runtime.orchestration.project;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.spi.ProjectAssistantRunCommand;
import com.buukle.agent.project.spi.ProjectAssistantRunPort;
import com.buukle.agent.project.spi.ProjectAssistantRunResult;
import com.buukle.agent.project.spi.ProjectAssistantSessionInfo;
import com.buukle.agent.project.spi.ProjectAssistantSessionPort;
import com.buukle.agent.project.spi.ProjectClarificationAnswerCommand;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Set;

/**
 * Runs the Project Assistant: canonical (system analysis) sessions and user forks.
 *
 * <p>The request always runs under the identity that is allowed to write the target session;
 * tenant scope stays inside one tenant and super-admin bypass is never enabled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAssistantRunService implements ProjectAssistantRunPort {

    private static final String SESSION_TYPE = SessionType.PROJECT_ROUTER;

    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ClarificationSpi clarificationSpi;
    private final UserSpi userSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final ProjectAssistantSessionPort projectSessionPort;

    @Override
    public ProjectAssistantRunResult runCanonical(ProjectAssistantRunCommand command) {
        return run(command, true);
    }

    @Override
    public ProjectAssistantRunResult runFork(ProjectAssistantRunCommand command) {
        return run(command, false);
    }

    private ProjectAssistantRunResult run(ProjectAssistantRunCommand command, boolean canonical) {
        if (command == null || command.sessionId() == null) {
            log.warn("Project assistant run rejected: session is required");
            return null;
        }
        SessionVO session = sessionSpi.getSession(command.sessionId());
        if (session == null || !SESSION_TYPE.equals(session.getSessionType())) {
            log.warn("Project assistant run rejected: session {} is not a project assistant",
                    command.sessionId());
            return null;
        }
        boolean isCanonical = session.getParentSessionId() == null;
        if (canonical != isCanonical) {
            log.warn("Project assistant run rejected: session {} canonical={} expected={}",
                    command.sessionId(), isCanonical, canonical);
            return null;
        }
        if (runSpi.findActiveRun(command.sessionId()) != null) {
            log.info("Project assistant run skipped: session {} is busy", command.sessionId());
            return null;
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        try {
            applyIdentity(command.tenantId(), command.requesterUserId(), command.requesterUsername());
            SendMessageDTO dto = new SendMessageDTO();
            dto.setMessage(command.message());
            dto.setThreadId(session.getThreadId());
            dto.setNoClarification(command.noClarification());
            dto.setRunMode(command.runMode());
            ChatMessageResponseVO response = chatRuntimeService.chat(command.sessionId(), dto);
            if (response == null || response.getRunId() == null) {
                return null;
            }
            if (ChatConstant.RESPONSE_STATUS_QUEUED.equals(response.getStatus())) {
                log.info("Project assistant run queued/rejected: session {}", command.sessionId());
                return null;
            }
            return new ProjectAssistantRunResult(command.sessionId(), response.getRunId());
        } catch (Exception e) {
            log.warn("Project assistant run failed: session={}, error={}",
                    command.sessionId(), e.getMessage());
            return null;
        } finally {
            snapshot.restore();
        }
    }

    @Override
    public void answerClarification(ProjectClarificationAnswerCommand command) {
        if (command == null || !StringUtils.hasText(command.clarificationId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "clarificationId is required");
        }
        ClarificationVO pending = clarificationSpi.findPendingByClarificationId(command.clarificationId());
        if (pending == null) {
            // Idempotent: another tab already answered, or the question expired.
            return;
        }
        if (!Objects.equals(pending.getTargetUserId(), command.answeringUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "this question is addressed to another project member");
        }
        SessionVO session = sessionSpi.getSessionShared(pending.getSessionId());
        if (session == null) {
            return;
        }
        ProjectAssistantSessionInfo link = projectSessionPort.findInfoBySessionId(session.getId());
        if (link == null || link.requesterUserId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "the original requester is missing from this project session");
        }
        Long requesterId = link.requesterUserId();
        ContextSnapshot snapshot = ContextSnapshot.capture();
        try {
            applyIdentity(link.tenantId() != null ? link.tenantId() : session.getTenantId(),
                    requesterId, usernameOf(requesterId));
            chatRuntimeService.resumeFromClarification(pending.getSessionId(), pending.getRunId(),
                    command.response(), command.clarificationId());
            clarificationSpi.markAnsweredBy(command.clarificationId(), command.answeringUserId());
        } finally {
            snapshot.restore();
        }
    }

    private void applyIdentity(Long tenantId, Long userId, String username) {
        AuthContext.clear();
        AuthContext.setUserId(userId);
        AuthContext.setUsername(username);
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(Set.of());
        TenantContext.set(tenantId);
        TenantUtil.start(username);
        TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "system";
        }
        UserIdentityVO identity = userSpi.identityMap(Set.of(userId)).get(userId);
        if (identity == null || !StringUtils.hasText(identity.getUsername())) {
            return String.valueOf(userId);
        }
        return identity.getUsername();
    }

    /** Snapshot/restore so a run initiated inside a request does not leak identity. */
    private record ContextSnapshot(
            String token,
            String username,
            String displayName,
            Long userId,
            boolean superAdmin,
            Set<String> permissions,
            Long tenantId,
            TenantScope scope,
            String tenantUtil,
            SessionSurface surface
    ) {
        static ContextSnapshot capture() {
            return new ContextSnapshot(
                    AuthContext.getToken(), AuthContext.getUsername(), AuthContext.getDisplayName(),
                    AuthContext.getUserId(), AuthContext.isSuperAdmin(), AuthContext.getPermissions(),
                    TenantContext.get(), TenantScopeHolder.get(), TenantUtil.get(),
                    SessionSurfaceHolder.current());
        }

        void restore() {
            AuthContext.clear();
            if (token != null) AuthContext.setToken(token);
            if (username != null) AuthContext.setUsername(username);
            if (displayName != null) AuthContext.setDisplayName(displayName);
            if (userId != null) AuthContext.setUserId(userId);
            AuthContext.setSuperAdmin(superAdmin);
            if (permissions != null) AuthContext.setPermissions(permissions);
            TenantContext.set(tenantId);
            if (scope == null) {
                TenantScopeHolder.clear();
            } else {
                TenantScopeHolder.start(scope);
            }
            if (tenantUtil == null) {
                TenantUtil.stop();
            } else {
                TenantUtil.start(tenantUtil);
            }
            if (surface == null) {
                SessionSurfaceHolder.clear();
            } else {
                SessionSurfaceHolder.set(surface);
            }
        }
    }
}
