package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.dict.SystemDicConstants;
import com.buukle.agent.common.dict.SystemDicEntry;
import com.buukle.agent.common.dict.SystemDicSpi;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.CreateSuperAgentDTO;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentInstancesVO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentVO;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.AgentRouterPromptService;
import com.buukle.agent.superagent.service.SuperAgentService;
import com.buukle.agent.superagent.service.support.SuperAgentPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentServiceImpl implements SuperAgentService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ROLE_ROUTER = "ROUTER";
    private static final String ROLE_WORKER = "WORKER";
    private static final String BUSINESS_TYPE = "SUPER_AGENT";

    private final AgentSuperAgentMapper superAgentMapper;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final AgentRouterPromptService agentRouterPromptService;
    private final RouteSpi routeSpi;
    private final SystemDicSpi systemDicSpi;

    @Value("${buukle.agent.super-agent.agent-router.max-loop-count:64}")
    private Integer routerMaxLoopCount;
    @Value("${buukle.agent.super-agent.worker.default-loop-limit:128}")
    private Integer workerDefaultLoopLimit;

    @Override
    @Transactional
    public SuperAgentVO create(CreateSuperAgentDTO dto) {
        Long userId = currentUserId();
        ModelRouteVO defaultRoute = routeSpi.getDefaultRoute();
        if (defaultRoute == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "default model route is not configured; complete resource initialization first");
        }
        Long modelRouteId = defaultRoute.getId();
        SystemDicEntry position = requirePosition(dto.getPositionCode());

        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setOwnerUserId(userId);
        agent.setAgentKey("agent-" + UUID.randomUUID().toString().substring(0, 8));
        agent.setName(dto.getName());
        agent.setDescription(dto.getDescription());
        agent.setPositionCode(position.code());
        agent.setPositionNameCn(position.nameCn());
        agent.setPositionNameEn(position.nameEn());
        agent.setConfig(dto.getConfig());
        agent.setStatus(SuperAgentStatus.ACTIVE);
        superAgentMapper.insert(agent);

        CreateInstanceDTO routerDTO = new CreateInstanceDTO();
        routerDTO.setName(dto.getName() + " Router");
        routerDTO.setDescription("Super-Agent router: " + dto.getName());
        routerDTO.setSystemPrompt(SuperAgentPrompt.withStrict(
                agentRouterPromptService.resolve(), position.nameEn()));
        routerDTO.setModelRouteId(modelRouteId);
        routerDTO.setInstanceRole(ROLE_ROUTER);
        routerDTO.setSystemManaged(false);
        routerDTO.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        routerDTO.setMaxLoopCount(routerMaxLoopCount);
        routerDTO.setBusinessType(BUSINESS_TYPE);
        routerDTO.setAllowConsoleSession(false);
        routerDTO.setAllowWidgetSession(false);
        InstanceVO routerInstance = instanceSpi.createInstance(routerDTO);

        CreateSessionDTO sessionDTO = new CreateSessionDTO();
        sessionDTO.setAgentInstanceId(routerInstance.getId());
        sessionDTO.setTitle(dto.getName() + " 守护会话");
        sessionDTO.setSessionType(ROLE_ROUTER);
        sessionDTO.setSuperAgentId(agent.getId());
        sessionDTO.setCreatedBy(AuthContext.getUsername());
        SessionVO session = createInternalSession(sessionDTO);

        CreateInstanceDTO workerDTO = new CreateInstanceDTO();
        workerDTO.setName(dto.getName() + " Worker");
        workerDTO.setDescription("Super-Agent worker: " + dto.getName());
        workerDTO.setSystemPrompt(SuperAgentPrompt.strict(position.nameEn()));
        workerDTO.setModelRouteId(modelRouteId);
        workerDTO.setInstanceRole(ROLE_WORKER);
        workerDTO.setSystemManaged(false);
        workerDTO.setCompletionProtocol(CompletionProtocol.STRICT_FINISH.name());
        workerDTO.setMaxLoopCount(workerDefaultLoopLimit);
        workerDTO.setBusinessType(BUSINESS_TYPE);
        workerDTO.setAllowConsoleSession(false);
        workerDTO.setAllowWidgetSession(false);
        InstanceVO workerInstance = instanceSpi.createInstance(workerDTO);

        agent.setRouterInstanceId(routerInstance.getId());
        agent.setRouterSessionId(session.getId());
        agent.setWorkerInstanceId(workerInstance.getId());
        superAgentMapper.updateById(agent);
        return toVO(agent);
    }

    @Override
    public List<SuperAgentVO> list() {
        return list(0);
    }

    @Override
    public List<SuperAgentVO> list(int limit) {
        Long userId = currentUserId();
        var query = Wrappers.<AgentSuperAgent>lambdaQuery()
                .ne(AgentSuperAgent::getStatus, SuperAgentStatus.ARCHIVED)
                .orderByDesc(AgentSuperAgent::getId);
        if (!AuthContext.isSuperAdmin()) {
            query.eq(AgentSuperAgent::getOwnerUserId, userId);
        }
        if (limit > 0) {
            query.last("LIMIT " + limit);
        }
        return superAgentMapper.selectList(query).stream().map(this::toVO).toList();
    }

    @Override
    public SuperAgentVO get(Long id) {
        return toVO(requireOwned(id));
    }

    @Override
    public SuperAgentInstancesVO getInstances(Long id) {
        AgentSuperAgent agent = requireOwned(id);
        SuperAgentInstancesVO vo = new SuperAgentInstancesVO();
        vo.setSuperAgentId(agent.getId());
        vo.setRouterInstance(findInstance(agent.getRouterInstanceId()));
        vo.setWorkerInstance(findInstance(agent.getWorkerInstanceId()));
        vo.setGuardianSession(findSession(agent.getRouterSessionId()));
        return vo;
    }

    private InstanceVO findInstance(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        try {
            return instanceSpi.getInstance(instanceId);
        } catch (Exception e) {
            return null;
        }
    }

    private SessionVO findSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return sessionSpi.getSession(sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public SuperAgentVO update(Long id, CreateSuperAgentDTO dto) {
        AgentSuperAgent agent = requireOwned(id);
        if (dto.getName() != null && !dto.getName().isBlank()) {
            agent.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            agent.setDescription(dto.getDescription());
        }
        if (dto.getPositionCode() != null && !dto.getPositionCode().isBlank()) {
            SystemDicEntry position = requirePosition(dto.getPositionCode());
            boolean changed = !position.code().equals(agent.getPositionCode())
                    || !position.nameCn().equals(agent.getPositionNameCn())
                    || !position.nameEn().equals(agent.getPositionNameEn());
            agent.setPositionCode(position.code());
            agent.setPositionNameCn(position.nameCn());
            agent.setPositionNameEn(position.nameEn());
            if (changed) {
                syncPositionPrompts(agent, position);
            }
        }
        if (dto.getConfig() != null) {
            agent.setConfig(dto.getConfig());
        }
        superAgentMapper.updateById(agent);
        return toVO(agent);
    }

    @Override
    public void delete(Long id) {
        AgentSuperAgent agent = requireOwned(id);
        agent.setStatus(SuperAgentStatus.ARCHIVED);
        superAgentMapper.updateById(agent);
    }

    @Override
    public SuperAgentVO pause(Long id) {
        AgentSuperAgent agent = requireOwned(id);
        agent.setStatus(SuperAgentStatus.PAUSED);
        superAgentMapper.updateById(agent);
        return toVO(agent);
    }

    @Override
    public SuperAgentVO resume(Long id) {
        AgentSuperAgent agent = requireOwned(id);
        agent.setStatus(SuperAgentStatus.ACTIVE);
        superAgentMapper.updateById(agent);
        return toVO(agent);
    }

    /**
     * Guardian sessions are created by the console request but are internal resources.
     * The target instance disables console/widget sessions, so the surface check must be bypassed.
     */
    private SessionVO createInternalSession(CreateSessionDTO dto) {
        SessionSurface previous = SessionSurfaceHolder.get();
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
        try {
            return sessionSpi.createSession(dto);
        } finally {
            if (previous == null) {
                SessionSurfaceHolder.clear();
            } else {
                SessionSurfaceHolder.set(previous);
            }
        }
    }

    private SystemDicEntry requirePosition(String positionCode) {
        if (positionCode == null || positionCode.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "position is required");
        }
        SystemDicEntry position = systemDicSpi.findActive(
                SystemDicConstants.TYPE_POSITION, positionCode.trim());
        if (position == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "unknown position: " + positionCode);
        }
        return position;
    }

    /** Position edits must be reflected in both managed instances' system prompts. */
    private void syncPositionPrompts(AgentSuperAgent agent, SystemDicEntry position) {
        updateInstancePrompt(agent.getRouterInstanceId(), SuperAgentPrompt.withStrict(
                agentRouterPromptService.resolve(), position.nameEn()));
        updateInstancePrompt(agent.getWorkerInstanceId(),
                SuperAgentPrompt.strict(position.nameEn()));
    }

    private void updateInstancePrompt(Long instanceId, String systemPrompt) {
        if (instanceId == null) {
            return;
        }
        InstanceVO instance;
        try {
            instance = instanceSpi.getInstance(instanceId);
        } catch (Exception e) {
            log.warn("Skip refreshing prompt for missing instance {}: {}", instanceId, e.getMessage());
            return;
        }
        if (instance == null) {
            return;
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName(instance.getName());
        dto.setSystemPrompt(systemPrompt);
        instanceSpi.updateInstance(instanceId, dto);
    }

    private AgentSuperAgent requireOwned(Long id) {
        AgentSuperAgent agent = superAgentMapper.selectById(id);
        if (agent == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin() && !currentUserId().equals(agent.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return agent;
    }

    private SuperAgentVO toVO(AgentSuperAgent agent) {
        SuperAgentVO vo = new SuperAgentVO();
        vo.setId(agent.getId());
        vo.setOwnerUserId(agent.getOwnerUserId());
        vo.setAgentKey(agent.getAgentKey());
        vo.setName(agent.getName());
        vo.setDescription(agent.getDescription());
        vo.setRouterInstanceId(agent.getRouterInstanceId());
        vo.setRouterSessionId(agent.getRouterSessionId());
        vo.setWorkerInstanceId(agent.getWorkerInstanceId());
        vo.setPositionCode(agent.getPositionCode());
        vo.setPositionNameCn(agent.getPositionNameCn());
        vo.setPositionNameEn(agent.getPositionNameEn());
        vo.setConfig(agent.getConfig());
        vo.setStatus(agent.getStatus());
        vo.setCreatedAt(agent.getCreatedAt() == null ? null : agent.getCreatedAt().format(DTF));
        return vo;
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
