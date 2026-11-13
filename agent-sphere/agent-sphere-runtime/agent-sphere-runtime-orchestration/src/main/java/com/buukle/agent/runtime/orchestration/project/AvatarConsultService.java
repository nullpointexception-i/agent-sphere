package com.buukle.agent.runtime.orchestration.project;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.AvatarConsultCommand;
import com.buukle.agent.superagent.spi.AvatarConsultResult;
import com.buukle.agent.superagent.spi.AvatarConsultSpi;
import com.buukle.agent.superagent.spi.AvatarInfo;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Synchronous avatar consultation backed by a short-lived read-only task session.
 *
 * <p>The avatar never receives the project tools, so it cannot consult another avatar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarConsultService implements AvatarConsultSpi {

    private static final long POLL_MILLIS = 500;
    private static final int MAX_TITLE = 120;

    private final SuperAgentDirectorySpi directorySpi;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ChatRuntimeService chatRuntimeService;

    @Override
    public AvatarConsultResult consult(AvatarConsultCommand command) {
        if (command == null || command.avatarId() == null || !StringUtils.hasText(command.question())) {
            return AvatarConsultResult.failed("avatarId and question are required");
        }
        AvatarInfo avatar = directorySpi.findAvatar(command.avatarId());
        if (avatar == null) {
            return AvatarConsultResult.failed("avatar not found");
        }
        if (!com.buukle.agent.common.constant.ProjectAssistantConstants.STATUS_ACTIVE
                .equals(avatar.status())) {
            return AvatarConsultResult.failed("avatar is not active");
        }
        Long instanceId = avatar.routerInstanceId();
        if (instanceId == null) {
            var router = instanceSpi.findByRole(
                    com.buukle.agent.instance.dtvo.enums.InstanceRole.GROUP_ROUTER.value());
            instanceId = router == null ? null : router.getId();
        }
        if (instanceId == null) {
            return AvatarConsultResult.failed("avatar has no runnable instance");
        }

        ContextSnapshot snapshot = ContextSnapshot.capture();
        Long sessionId = null;
        try {
            applyIdentity(command.tenantId(), command.requesterUserId(), command.requesterUsername());
            SessionVO session = sessionSpi.createSession(newSession(instanceId, command));
            sessionId = session.getId();
            SendMessageDTO dto = new SendMessageDTO();
            dto.setMessage(command.question());
            dto.setNoClarification(true);
            dto.setRunMode(RunMode.READ_ONLY.name());
            ChatMessageResponseVO response = chatRuntimeService.chat(sessionId, dto);
            if (response == null || response.getRunId() == null
                    || ChatConstant.RESPONSE_STATUS_QUEUED.equals(response.getStatus())) {
                return AvatarConsultResult.failed("avatar is busy");
            }
            return await(sessionId, response.getRunId(), command.timeoutSeconds());
        } catch (Exception e) {
            log.warn("Avatar consultation failed: avatar={}, error={}", command.avatarId(), e.getMessage());
            return AvatarConsultResult.failed("avatar consultation failed: " + e.getMessage());
        } finally {
            if (sessionId != null) {
                try {
                    sessionSpi.closeSession(sessionId);
                } catch (Exception ignored) {
                    // best effort
                }
            }
            snapshot.restore();
        }
    }

    private AvatarConsultResult await(Long sessionId, Long runId, long timeoutSeconds) {
        long timeout = timeoutSeconds <= 0 ? 45 : timeoutSeconds;
        long deadline = System.currentTimeMillis() + timeout * 1000;
        while (System.currentTimeMillis() < deadline) {
            RunVO run = runSpi.getRun(runId);
            if (run != null) {
                String status = run.getStatus();
                if (RunStatus.COMPLETED.name().equals(status)) {
                    String reply = run.getAssistantReply();
                    return AvatarConsultResult.ok(reply == null ? "" : reply);
                }
                if (RunStatus.FAILED.name().equals(status) || RunStatus.CANCELLED.name().equals(status)) {
                    return AvatarConsultResult.failed(run.getErrorMessage() == null
                            ? "avatar run " + status : run.getErrorMessage());
                }
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AvatarConsultResult.failed("consultation interrupted");
            }
        }
        return AvatarConsultResult.failed("avatar consultation timed out");
    }

    private CreateSessionDTO newSession(Long instanceId, AvatarConsultCommand command) {
        CreateSessionDTO dto = new CreateSessionDTO();
        dto.setAgentInstanceId(instanceId);
        dto.setTitle(truncate("Consult " + command.avatarId() + ": " + command.question()));
        dto.setSessionType(SessionType.TASK);
        dto.setCreatedBy(command.requesterUsername());
        dto.setOverrideModelRouteId(null);
        return dto;
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

    private static String truncate(String value) {
        if (value == null) {
            return "Avatar consultation";
        }
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE);
    }

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
