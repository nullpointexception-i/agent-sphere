package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntimeService {

    private final RuntimeOrchestrator orchestrator;
    private final RunSpi runSpi;
    private final SessionSpi sessionSpi;
    private final ApplicationEventPublisher eventPublisher;

    public ChatMessageResponseVO chat(Long sessionId, SendMessageDTO dto) {
        log.info("Chat request: sessionId={}, message={}", sessionId, dto.getMessage());

        assertSessionOwnership(sessionId);
        sessionSpi.touchSession(sessionId);
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setUserMessage(dto.getMessage());
        createRunDTO.setType(RunEnum.TYPE_AUTO);
        RunVO run = runSpi.createRun(createRunDTO);
        if (dto.getDelivery() != null) {
            run.setDelivery(dto.getDelivery());
        }
        log.info("Run created: runId={}, type={}", run.getId(), run.getType());

        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(run.getId())
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())));

        orchestrator.asyncHandleUserMessage(run, sessionId, dto.getMessage(), dto.getModelRouteId());
        log.info("Async execution started for runId={}", run.getId());

        ChatMessageResponseVO response = new ChatMessageResponseVO();
        response.setRunId(run.getId());
        response.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
        return response;
    }

    private void assertSessionOwnership(Long sessionId) {
        if (AuthContext.isSuperAdmin()) return;
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !AuthContext.getUsername().equals(session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
