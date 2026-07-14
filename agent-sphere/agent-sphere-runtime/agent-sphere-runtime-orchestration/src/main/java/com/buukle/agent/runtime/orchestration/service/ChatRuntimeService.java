package com.buukle.agent.runtime.orchestration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntimeService {

    private final RuntimeOrchestrator orchestrator;
    private final RunSpi runSpi;
    private final SessionSpi sessionSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentPendingClarificationMapper clarificationMapper;

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

    public ChatMessageResponseVO resumeFromClarification(Long sessionId, Long runId, String response, String clarificationId) {
        assertSessionOwnership(sessionId);

        LambdaQueryWrapper<AgentPendingClarification> query = new LambdaQueryWrapper<AgentPendingClarification>()
                .eq(AgentPendingClarification::getRunId, runId)
                .isNull(AgentPendingClarification::getUserResponse)
                .orderByDesc(AgentPendingClarification::getCreatedAt);
        if (clarificationId != null && !clarificationId.isBlank()) {
            query.eq(AgentPendingClarification::getClarificationId, clarificationId);
        }
        AgentPendingClarification pending = clarificationMapper.selectOne(
                query.last(ChatClarification.CLARIFYING_SQL_LIMIT));
        if (pending == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, ChatClarification.CLARIFYING_ERROR_NOT_FOUND);
        }
        if (pending.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, ChatClarification.CLARIFYING_ERROR_EXPIRED);
        }
        pending.setUserResponse(response);
        clarificationMapper.updateById(pending);

        // Notify frontend which clarification was responded and with what text
        eventPublisher.publishEvent(new RuntimeEventVO(
                ClarificationStatus.RESPONDED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setClarificationId(pending.getClarificationId())
                        .setResponse(response)));

        sessionSpi.touchSession(sessionId);

        // Try to resume the original run; fall back to forking a new run.
        RunVO originalRun = runSpi.getRun(runId);
        if (originalRun != null && RunStatus.AWAITING_USER.name().equals(originalRun.getStatus())) {
            originalRun.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(originalRun);
            log.info("Clarification resume: reusing runId={} for session={}", runId, sessionId);
            orchestrator.asyncHandleUserMessage(originalRun, sessionId,
                    ChatClarification.CLARIFICATION_RESUME_PREFIX + response, null);

            ChatMessageResponseVO result = new ChatMessageResponseVO();
            result.setRunId(runId);
            result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
            return result;
        }

        // Fork — create a new run
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setUserMessage(ChatClarification.CLARIFICATION_RESUME_PREFIX + response);
        createRunDTO.setType(RunEnum.TYPE_AUTO);
        RunVO run = runSpi.createRun(createRunDTO);
        log.info("Clarification fork: new runId={} for session={} from previous runId={}", run.getId(), sessionId, runId);

        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(run.getId())
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())));

        orchestrator.asyncHandleUserMessage(run, sessionId, ChatClarification.CLARIFICATION_RESUME_PREFIX + response, null);

        ChatMessageResponseVO result = new ChatMessageResponseVO();
        result.setRunId(run.getId());
        result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
        return result;
    }

    public void stopRun(Long sessionId, Long runId) {
        assertSessionOwnership(sessionId);
        RunVO run = runSpi.getRun(runId);
        if (run == null) return;

        if (RunStatus.AWAITING_USER.name().equals(run.getStatus())) {
            run.setStatus(RunStatus.CANCELLED.name());
            runSpi.updateRun(run);

            List<AgentPendingClarification> pendingList = clarificationMapper.selectList(
                    new LambdaQueryWrapper<AgentPendingClarification>()
                            .eq(AgentPendingClarification::getRunId, runId)
                            .isNull(AgentPendingClarification::getUserResponse));
            for (AgentPendingClarification pending : pendingList) {
                pending.setUserResponse(ChatClarification.CLARIFICATION_RESPONSE_DISMISSED);
                clarificationMapper.updateById(pending);
                eventPublisher.publishEvent(new RuntimeEventVO(
                        ClarificationStatus.DISMISSED,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(runId)
                                .setClarificationId(pending.getClarificationId())));
            }

            eventPublisher.publishEvent(new RuntimeEventVO(
                    RunStatus.CANCELLED,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(runId)
                            .setAssistantReply(RunnerConstants.CANCEL_MSG)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId)));
        } else {
            SessionRunner.cancelRun(runId);
        }
    }

    private void assertSessionOwnership(Long sessionId) {
        if (AuthContext.isSuperAdmin()) return;
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !AuthContext.getUsername().equals(session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
