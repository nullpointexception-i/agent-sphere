package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.GroupRouterRunCommand;
import com.buukle.agent.superagent.spi.GroupRouterRunPort;
import com.buukle.agent.superagent.spi.GroupRouterRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Executes Group Router decisions through the normal runtime.
 *
 * <p>The GROUP_ROUTER instance is a regular agent instance: the runtime assembles its system
 * prompt, records the run/timeline and the LLM interaction. This service submits one batch and
 * waits (bounded) for the run to finish so the scheduler can parse the JSON decision immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupRouterRunService implements GroupRouterRunPort {

    private static final int MAX_MESSAGE = 12000;
    private static final long POLL_INTERVAL_MS = 500L;
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            RunStatus.COMPLETED.name(),
            RunStatus.FAILED.name(),
            RunStatus.CANCELLED.name());

    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ChatRuntimeService chatRuntimeService;

    @Value("${buukle.agent.super-agent.group-router.run-timeout-ms:60000}")
    private long runTimeoutMs;

    @Override
    public GroupRouterRunResult route(GroupRouterRunCommand command) {
        if (command == null || command.sessionId() == null) {
            return GroupRouterRunResult.rejected("sessionId is required");
        }
        if (command.input() == null || command.input().isBlank()) {
            return GroupRouterRunResult.rejected("input is required");
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        SessionSurface previousSurface = SessionSurfaceHolder.current();
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
        try {
            SessionVO session = sessionSpi.getSession(command.sessionId());
            if (session == null || !SessionType.GROUP_ROUTER.equals(session.getSessionType())) {
                return GroupRouterRunResult.rejected("group router session not found");
            }
            AuthContext.setSuperAdmin(true);
            if (session.getCreatedBy() != null) {
                AuthContext.setUsername(session.getCreatedBy());
            }
            // Keep the run in the thread's tenant so the timeline / usage are visible there.
            TenantContext.set(command.tenantId());
            TenantScopeHolder.start(TenantScope.tenantOnly(
                    command.tenantId(), session.getCreatedBy()));
            if (runSpi.findActiveRun(command.sessionId()) != null) {
                return GroupRouterRunResult.rejected("group router is busy");
            }
            SendMessageDTO message = new SendMessageDTO();
            message.setMessage(truncate(command.input()));
            message.setThreadId(command.threadId());
            message.setRunMode(RunMode.READ_ONLY.name());
            message.setNoClarification(true);

            ChatMessageResponseVO response = chatRuntimeService.chat(command.sessionId(), message);
            if (response == null || response.getRunId() == null) {
                return GroupRouterRunResult.rejected("group router run was not created");
            }
            if (ChatConstant.RESPONSE_STATUS_QUEUED.equals(response.getStatus())) {
                return GroupRouterRunResult.rejected("group router is busy");
            }
            return awaitRun(command.sessionId(), response.getRunId());
        } catch (Exception e) {
            log.warn("Group router run failed: session={}, error={}", command.sessionId(), e.getMessage());
            return GroupRouterRunResult.rejected(e.getMessage());
        } finally {
            SessionSurfaceHolder.set(previousSurface);
            TenantUtil.stop();
            TenantScopeHolder.clear();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    private GroupRouterRunResult awaitRun(Long sessionId, Long runId) {
        long deadline = System.currentTimeMillis() + Math.max(1L, runTimeoutMs);
        while (System.currentTimeMillis() < deadline) {
            RunVO run;
            try {
                run = runSpi.getRun(runId);
            } catch (Exception e) {
                return GroupRouterRunResult.rejected("group router run not found");
            }
            if (run != null && TERMINAL_STATUSES.contains(run.getStatus())) {
                if (RunStatus.COMPLETED.name().equals(run.getStatus())) {
                    return GroupRouterRunResult.accepted(runId, run.getAssistantReply());
                }
                return GroupRouterRunResult.rejected(run.getErrorMessage() == null
                        ? "group router run " + run.getStatus() : run.getErrorMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                chatRuntimeService.stopRun(sessionId, runId);
                return GroupRouterRunResult.rejected("group router run interrupted");
            }
        }
        chatRuntimeService.stopRun(sessionId, runId);
        return GroupRouterRunResult.rejected("group router run timed out");
    }

    private static String truncate(String text) {
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE);
    }
}
