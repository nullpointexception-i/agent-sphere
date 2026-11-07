package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.DispatchResult;
import com.buukle.agent.superagent.spi.WorkerBindingPort;
import com.buukle.agent.superagent.spi.WorkerDispatchCommand;
import com.buukle.agent.superagent.spi.WorkerDispatchPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Worker dispatch: create a dedicated Worker Session for a Work Item and start one run.
 *
 * <p>superagent-service depends only on {@link WorkerDispatchPort}; this class, inside orchestration,
 * reuses {@link ChatRuntimeService}'s session/run capabilities. A Worker session is a system-internal session
 * ({@code sessionType=WORKER}); the request context is explicitly cleared while creating and starting it, so user identity
 * inherited by the scheduling thread is not carried into the background task.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerDispatchService implements WorkerDispatchPort {

    private static final int MAX_TITLE = 200;
    private static final int MAX_MESSAGE = 5000;

    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final ObjectProvider<WorkerBindingPort> bindingPortProvider;

    @Override
    public DispatchResult dispatch(WorkerDispatchCommand command) {
        if (command == null || command.workItemId() == null) {
            return DispatchResult.rejected("workItemId is required");
        }
        if (command.workerInstanceId() == null) {
            return DispatchResult.rejected("workerInstanceId is required");
        }
        if (command.goal() == null || command.goal().isBlank()) {
            return DispatchResult.rejected("goal is required");
        }

        TenantUtil.stop();
        TenantContext.clear();
        AuthContext.clear();
        if (instanceSpi.getInstance(command.workerInstanceId()) == null) {
            return DispatchResult.rejected("worker instance not found: " + command.workerInstanceId());
        }

        try {
            TenantContext.set(command.tenantId());
            AuthContext.setSuperAdmin(true);
            if (command.ownerUserId() != null) {
                AuthContext.setUserId(command.ownerUserId());
            }
            if (command.ownerUsername() != null && !command.ownerUsername().isBlank()) {
                AuthContext.setUsername(command.ownerUsername());
            }

            CreateSessionDTO sessionDTO = new CreateSessionDTO();
            sessionDTO.setAgentInstanceId(command.workerInstanceId());
            sessionDTO.setTitle(workerTitle(command));
            sessionDTO.setSessionType(SessionType.WORKER);
            sessionDTO.setSuperAgentId(command.superAgentId());
            sessionDTO.setThreadId(command.threadId());
            SessionVO session = sessionSpi.createSession(sessionDTO);

            WorkerBindingPort binding = bindingPortProvider.getIfAvailable();
            if (binding != null) {
                binding.bind(command.workItemId(), session.getId());
            }
            sessionSpi.bindWorkItem(session.getId(), command.workItemId());

            SendMessageDTO message = new SendMessageDTO();
            message.setMessage(truncate(command.goal(), MAX_MESSAGE));
            message.setRunMode(command.runMode() == null ? "WRITE" : command.runMode());
            message.setNoClarification(true);
            ChatMessageResponseVO response = chatRuntimeService.chat(session.getId(), message);
            return DispatchResult.ok(session.getId(), response == null ? null : response.getRunId());
        } catch (Exception e) {
            log.warn("Worker run start failed: workItem={}, error={}", command.workItemId(), e.getMessage());
            return DispatchResult.rejected(e.getMessage());
        } finally {
            AuthContext.clear();
            TenantUtil.stop();
            TenantContext.clear();
        }
    }

    private static String workerTitle(WorkerDispatchCommand command) {
        String goal = command.goal() == null ? "" : command.goal().replace('\n', ' ').trim();
        String title = "Worker: " + (goal.isBlank() ? "#" + command.workItemId() : goal);
        return truncate(title, MAX_TITLE);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
