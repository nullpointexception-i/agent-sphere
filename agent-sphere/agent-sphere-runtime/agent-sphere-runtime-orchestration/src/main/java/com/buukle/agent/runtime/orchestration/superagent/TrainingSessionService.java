package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.TrainingChatResult;
import com.buukle.agent.superagent.spi.TrainingMessageCommand;
import com.buukle.agent.superagent.spi.TrainingSessionCommand;
import com.buukle.agent.superagent.spi.TrainingSessionInfo;
import com.buukle.agent.superagent.spi.TrainingSessionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Creates and drives TRAINING sessions on the Worker instance.
 *
 * <p>The Worker instance intentionally has {@code allowConsoleSession=false}.
 * Training is a server-authorised exception: the session is created and driven
 * with an INTERNAL surface, while the caller has already verified avatar
 * ownership.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingSessionService implements TrainingSessionPort {

    private static final int MAX_TITLE = 200;

    private final SessionSpi sessionSpi;
    private final ChatRuntimeService chatRuntimeService;

    @Override
    public TrainingSessionInfo ensureSession(TrainingSessionCommand command) {
        if (command == null || command.superAgentId() == null
                || command.workerInstanceId() == null) {
            throw new IllegalArgumentException("training session command is incomplete");
        }
        SessionVO existing = sessionSpi.findActiveTrainingSession(
                command.superAgentId(), command.ownerUsername());
        if (existing != null) {
            return new TrainingSessionInfo(existing.getId(), existing.getStatus());
        }
        SessionVO created = withInternalSurface(() -> {
            CreateSessionDTO dto = new CreateSessionDTO();
            dto.setAgentInstanceId(command.workerInstanceId());
            dto.setTitle(trainingTitle(command.agentName()));
            dto.setSessionType(SessionType.TRAINING);
            dto.setSuperAgentId(command.superAgentId());
            dto.setCreatedBy(command.ownerUsername());
            return sessionSpi.createSession(dto);
        });
        if (created == null || created.getId() == null) {
            throw new IllegalStateException("failed to create training session");
        }
        log.info("Training session created: agent={}, session={}, owner={}",
                command.superAgentId(), created.getId(), command.ownerUsername());
        return new TrainingSessionInfo(created.getId(), created.getStatus());
    }

    @Override
    public TrainingChatResult send(Long sessionId, TrainingMessageCommand command) {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setMessage(command.message());
        dto.setRunMode(command.runMode());
        dto.setModelRouteId(command.modelRouteId());
        dto.setReasoningEffort(command.reasoningEffort());
        ChatMessageResponseVO response = withInternalSurface(
                () -> chatRuntimeService.chat(sessionId, dto));
        return new TrainingChatResult(
                response == null ? null : response.getRunId(),
                response == null ? null : response.getStatus());
    }

    @Override
    public void reset(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionSpi.closeSession(sessionId);
    }

    private static String trainingTitle(String agentName) {
        String name = agentName == null || agentName.isBlank() ? "Avatar" : agentName.trim();
        String title = "Training · " + name;
        return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
    }

    private static <T> T withInternalSurface(java.util.function.Supplier<T> action) {
        SessionSurface previous = SessionSurfaceHolder.get();
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SessionSurfaceHolder.clear();
            } else {
                SessionSurfaceHolder.set(previous);
            }
        }
    }
}
