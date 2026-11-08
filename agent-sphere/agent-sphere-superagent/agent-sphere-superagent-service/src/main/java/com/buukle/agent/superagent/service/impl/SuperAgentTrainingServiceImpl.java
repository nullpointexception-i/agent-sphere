package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.TrainingMessageDTO;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.vo.TrainingSessionVO;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.SuperAgentTrainingService;
import com.buukle.agent.superagent.spi.TrainingChatResult;
import com.buukle.agent.superagent.spi.TrainingMessageCommand;
import com.buukle.agent.superagent.spi.TrainingSessionCommand;
import com.buukle.agent.superagent.spi.TrainingSessionInfo;
import com.buukle.agent.superagent.spi.TrainingSessionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentTrainingServiceImpl implements SuperAgentTrainingService {

    private final AgentSuperAgentMapper superAgentMapper;
    private final SessionSpi sessionSpi;
    private final ObjectProvider<TrainingSessionPort> trainingPortProvider;

    @Override
    public TrainingSessionVO ensureSession(Long agentId) {
        AgentSuperAgent agent = requireTrainable(agentId);
        TrainingSessionPort port = requirePort();
        TrainingSessionInfo info = port.ensureSession(new TrainingSessionCommand(
                agent.getId(), agent.getWorkerInstanceId(), agent.getTenantId(),
                currentUserId(), currentUsername(), agent.getName()));
        TrainingSessionVO vo = new TrainingSessionVO();
        vo.setSessionId(info.sessionId());
        vo.setSuperAgentId(agent.getId());
        vo.setWorkerInstanceId(agent.getWorkerInstanceId());
        vo.setStatus(info.status());
        return vo;
    }

    @Override
    public TrainingChatResult send(Long agentId, Long sessionId, TrainingMessageDTO dto) {
        requireOwned(agentId);
        SessionVO session = requireTrainingSession(agentId, sessionId);
        log.debug("Training message: agent={}, session={}, owner={}",
                agentId, session.getId(), currentUsername());
        return requirePort().send(sessionId, new TrainingMessageCommand(
                dto.getMessage(), dto.getRunMode(), dto.getModelRouteId(),
                dto.getReasoningEffort()));
    }

    @Override
    public void reset(Long agentId, Long sessionId) {
        requireOwned(agentId);
        requireTrainingSession(agentId, sessionId);
        requirePort().reset(sessionId);
    }

    private AgentSuperAgent requireTrainable(Long agentId) {
        AgentSuperAgent agent = requireOwned(agentId);
        if (SuperAgentStatus.ARCHIVED.equals(agent.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "archived avatar cannot enter training mode");
        }
        if (agent.getWorkerInstanceId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "avatar has no worker instance");
        }
        return agent;
    }

    private SessionVO requireTrainingSession(Long agentId, Long sessionId) {
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !SessionType.TRAINING.equals(session.getSessionType())
                || !Objects.equals(agentId, session.getSuperAgentId())
                || !Objects.equals(currentUsername(), session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return session;
    }

    private AgentSuperAgent requireOwned(Long agentId) {
        AgentSuperAgent agent = superAgentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin()
                && !Objects.equals(currentUserId(), agent.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return agent;
    }

    private TrainingSessionPort requirePort() {
        TrainingSessionPort port = trainingPortProvider.getIfAvailable();
        if (port == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "training runtime is unavailable");
        }
        return port;
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private static String currentUsername() {
        String username = AuthContext.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return username;
    }
}
