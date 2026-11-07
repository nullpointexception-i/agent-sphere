package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.dtvo.enums.AgentRouterRunStatus;
import com.buukle.agent.superagent.spi.AgentRouterRunCommand;
import com.buukle.agent.superagent.spi.AgentRouterRunPort;
import com.buukle.agent.superagent.spi.AgentRouterRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Starts runs on existing Agent Router sessions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRouterRunService implements AgentRouterRunPort {

    private static final int MAX_MESSAGE = 5000;

    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ChatRuntimeService chatRuntimeService;

    @Override
    public AgentRouterRunResult start(AgentRouterRunCommand command) {
        if (command == null || command.sessionId() == null) {
            return AgentRouterRunResult.rejected("sessionId is required");
        }
        if (command.input() == null || command.input().isBlank()) {
            return AgentRouterRunResult.rejected("input is required");
        }
        TenantUtil.stop();
        TenantContext.clear();
        AuthContext.clear();
        try {
            SessionVO session = sessionSpi.getSession(command.sessionId());
            if (session == null || !SessionType.ROUTER.equals(session.getSessionType())) {
                return AgentRouterRunResult.rejected("router session not found");
            }
            AuthContext.setSuperAdmin(true);
            if (session.getCreatedBy() != null) {
                AuthContext.setUsername(session.getCreatedBy());
            }
            // Park guard: a pending clarification/approval card waits for the user in the
            // DM and resumes its own run. Starting another run here would fork the
            // conversation (the parked run holds the question context).
            RunVO activeRun = runSpi.findActiveRun(command.sessionId());
            if (activeRun != null && RunStatus.AWAITING_USER.name().equals(activeRun.getStatus())) {
                log.info("Agent router start rejected: session={}, run={} awaiting user input",
                        command.sessionId(), activeRun.getId());
                return AgentRouterRunResult.rejected("router is awaiting user input");
            }
            SendMessageDTO message = new SendMessageDTO();
            message.setMessage(truncate(command.input()));
            message.setThreadId(command.threadId());
            message.setRunMode(command.runMode() == null
                    ? RunMode.WRITE.name() : command.runMode());
            ChatMessageResponseVO response = chatRuntimeService.chat(command.sessionId(), message);
            return AgentRouterRunResult.accepted(
                    response == null ? null : response.getRunId(),
                    response == null ? AgentRouterRunStatus.QUEUED : response.getStatus());
        } catch (Exception e) {
            log.warn("Agent router run failed: session={}, error={}",
                    command.sessionId(), e.getMessage());
            return AgentRouterRunResult.rejected(e.getMessage());
        } finally {
            TenantUtil.stop();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE);
    }
}
