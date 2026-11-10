package com.buukle.agent.superagent.service.impl;


import com.buukle.agent.instance.dtvo.enums.InstanceRole;import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.constant.OwnerScope;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.dict.SystemDicConstants;
import com.buukle.agent.common.dict.SystemDicEntry;
import com.buukle.agent.common.dict.SystemDicSpi;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.event.InstanceDeletedEvent;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.CreateSuperAgentDTO;
import com.buukle.agent.superagent.dtvo.enums.AgentType;
import com.buukle.agent.superagent.dtvo.enums.LocalRuntimeStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentInstancesVO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentPublicVO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentVO;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.AgentRouterPromptService;
import com.buukle.agent.superagent.service.SuperAgentService;
import com.buukle.agent.superagent.service.support.SuperAgentPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentServiceImpl implements SuperAgentService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ROLE_ROUTER = InstanceRole.ROUTER.value();
    private static final String ROLE_WORKER = InstanceRole.WORKER.value();
    private static final String BUSINESS_TYPE = "SUPER_AGENT";

    private final AgentSuperAgentMapper superAgentMapper;
    private final InstanceSpi instanceSpi;
    private final AgentRouterPromptService agentRouterPromptService;
    private final RouteSpi routeSpi;
    private final SystemDicSpi systemDicSpi;
    private final AgentLocalRuntimeMapper localRuntimeMapper;

    @Value("${buukle.agent.super-agent.agent-router.max-loop-count:64}")
    private Integer routerMaxLoopCount;
    @Value("${buukle.agent.super-agent.worker.default-loop-limit:128}")
    private Integer workerDefaultLoopLimit;

    @Override
    @Transactional
    public SuperAgentVO create(CreateSuperAgentDTO dto) {
        Long userId = currentUserId();
        SystemDicEntry position = requirePosition(dto.getPositionCode());
        String agentType = normalizeAgentType(dto.getAgentType());

        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setOwnerUserId(userId);
        agent.setAgentKey("agent-" + UUID.randomUUID().toString().substring(0, 8));
        agent.setAgentType(agentType);
        agent.setName(dto.getName());
        agent.setDescription(dto.getDescription());
        agent.setPositionCode(position.code());
        agent.setPositionNameCn(position.nameCn());
        agent.setPositionNameEn(position.nameEn());
        agent.setConfig(dto.getConfig());
        agent.setStatus(SuperAgentStatus.ACTIVE);

        if (AgentType.LOCAL.equals(agentType)) {
            Long runtimeId = dto.getLocalRuntimeId();
            if (runtimeId == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "localRuntimeId is required for a local avatar");
            }
            AgentLocalRuntime runtime = localRuntimeMapper.selectById(runtimeId);
            if (runtime == null || !LocalRuntimeStatus.ACTIVE.equals(runtime.getStatus())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "local runtime not found or disabled");
            }
            if (!userId.equals(runtime.getOwnerUserId())) {
                throw new BizException(CommonErrorCode.FORBIDDEN);
            }
            agent.setLocalRuntimeId(runtimeId);
            superAgentMapper.insert(agent);
            return toVO(agent);
        }

        ModelRouteVO defaultRoute = routeSpi.getDefaultRoute();
        if (defaultRoute == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "default model route is not configured; complete resource initialization first");
        }
        Long modelRouteId = defaultRoute.getId();

        CreateInstanceDTO routerDTO = new CreateInstanceDTO();
        routerDTO.setName(dto.getName() + " Router");
        routerDTO.setDescription("Super-Agent router: " + dto.getName());
        routerDTO.setSystemPrompt(SuperAgentPrompt.routerPrompt(
                position.nameEn(), dto.getDescription(), agentRouterPromptService.resolve()));
        routerDTO.setModelRouteId(modelRouteId);
        routerDTO.setInstanceRole(ROLE_ROUTER);
        routerDTO.setOwnerScope(OwnerScope.USER);
        routerDTO.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        routerDTO.setMaxLoopCount(routerMaxLoopCount);
        routerDTO.setBusinessType(BUSINESS_TYPE);
        routerDTO.setAllowConsoleSession(false);
        routerDTO.setAllowWidgetSession(false);
        InstanceVO routerInstance = instanceSpi.createInstance(routerDTO);

        CreateInstanceDTO workerDTO = new CreateInstanceDTO();
        workerDTO.setName(dto.getName() + " Worker");
        workerDTO.setDescription("Super-Agent worker: " + dto.getName());
        workerDTO.setSystemPrompt(SuperAgentPrompt.workerPrompt(
                position.nameEn(), dto.getDescription()));
        workerDTO.setModelRouteId(modelRouteId);
        workerDTO.setInstanceRole(ROLE_WORKER);
        workerDTO.setOwnerScope(OwnerScope.USER);
        workerDTO.setCompletionProtocol(CompletionProtocol.STRICT_FINISH.name());
        workerDTO.setMaxLoopCount(workerDefaultLoopLimit);
        workerDTO.setBusinessType(BUSINESS_TYPE);
        workerDTO.setAllowConsoleSession(false);
        workerDTO.setAllowWidgetSession(false);
        InstanceVO workerInstance = instanceSpi.createInstance(workerDTO);

        agent.setRouterInstanceId(routerInstance.getId());
        agent.setWorkerInstanceId(workerInstance.getId());
        superAgentMapper.insert(agent);
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

    @Override
    public SuperAgentVO update(Long id, CreateSuperAgentDTO dto) {
        AgentSuperAgent agent = requireOwned(id);
        boolean promptsChanged = false;
        if (dto.getName() != null && !dto.getName().isBlank()) {
            agent.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            promptsChanged = !dto.getDescription().equals(agent.getDescription());
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
            promptsChanged = promptsChanged || changed;
        }
        if (dto.getConfig() != null) {
            agent.setConfig(dto.getConfig());
        }
        if (promptsChanged) {
            syncPrompts(agent);
        }
        superAgentMapper.updateById(agent);
        return toVO(agent);
    }

    @Override
    @Transactional
    public SuperAgentVO updateModelRoute(Long id, Long modelRouteId) {
        AgentSuperAgent agent = requireOwned(id);
        if (modelRouteId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "modelRouteId is required");
        }
        boolean updated = false;
        if (agent.getRouterInstanceId() != null) {
            instanceSpi.setModelRoute(agent.getRouterInstanceId(), modelRouteId);
            updated = true;
        }
        if (agent.getWorkerInstanceId() != null) {
            instanceSpi.setModelRoute(agent.getWorkerInstanceId(), modelRouteId);
            updated = true;
        }
        if (!updated) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "avatar has no managed instances");
        }
        return toVO(agent);
    }

    /**
     * Deleting a managed Router/Worker leaves the avatar without its execution chain. Archive the
     * owning avatar so it cannot be scheduled with a dangling instance reference.
     */
    @EventListener
    public void onInstanceDeleted(InstanceDeletedEvent event) {
        Long instanceId = event == null ? null : event.getInstanceId();
        if (instanceId == null) {
            return;
        }
        List<AgentSuperAgent> agents = superAgentMapper.selectList(
                Wrappers.<AgentSuperAgent>lambdaQuery()
                        .and(w -> w.eq(AgentSuperAgent::getRouterInstanceId, instanceId)
                                .or().eq(AgentSuperAgent::getWorkerInstanceId, instanceId)));
        for (AgentSuperAgent agent : agents) {
            if (!SuperAgentStatus.ARCHIVED.equals(agent.getStatus())) {
                agent.setStatus(SuperAgentStatus.ARCHIVED);
                superAgentMapper.updateById(agent);
                log.info("Archived super agent {} after managed instance {} was deleted",
                        agent.getId(), instanceId);
            }
        }
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

    @Override
    public SuperAgentVO updateVisibility(Long id, boolean publicVisible) {
        AgentSuperAgent agent = requireOwned(id);
        agent.setPublicVisible(publicVisible);
        superAgentMapper.updateById(agent);
        return toVO(agent);
    }

    @Override
    public List<SuperAgentPublicVO> listPublic(List<Long> ownerUserIds) {
        LinkedHashSet<Long> owners = new LinkedHashSet<>();
        if (ownerUserIds != null) {
            for (Long ownerUserId : ownerUserIds) {
                if (ownerUserId != null) {
                    owners.add(ownerUserId);
                }
            }
        }
        if (owners.isEmpty()) {
            owners.add(currentUserId());
        }
        List<AgentSuperAgent> agents = superAgentMapper.selectList(
                Wrappers.<AgentSuperAgent>lambdaQuery()
                        .in(AgentSuperAgent::getOwnerUserId, owners)
                        .eq(AgentSuperAgent::getPublicVisible, true)
                        .eq(AgentSuperAgent::getStatus, SuperAgentStatus.ACTIVE)
                        .orderByDesc(AgentSuperAgent::getId));
        List<SuperAgentPublicVO> result = new ArrayList<>(agents.size());
        for (AgentSuperAgent agent : agents) {
            SuperAgentPublicVO vo = new SuperAgentPublicVO();
            vo.setId(agent.getId());
            vo.setOwnerUserId(agent.getOwnerUserId());
            vo.setAgentType(agent.getAgentType());
            vo.setName(agent.getName());
            vo.setDescription(agent.getDescription());
            vo.setPositionCode(agent.getPositionCode());
            vo.setPositionNameCn(agent.getPositionNameCn());
            vo.setPositionNameEn(agent.getPositionNameEn());
            vo.setStatus(agent.getStatus());
            result.add(vo);
        }
        return result;
    }

    private SystemDicEntry requirePosition(String positionCode) {
        if (positionCode == null || positionCode.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "role is required");
        }
        SystemDicEntry position = systemDicSpi.findActive(
                SystemDicConstants.TYPE_POSITION, positionCode.trim());
        if (position == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "unknown role: " + positionCode);
        }
        return position;
    }

    /**
     * Refresh managed instance prompts on startup.
     *
     * <p>The global router prompt is baked into the Router instance's system prompt when
     * that instance is created or its role/scope is edited. Without this refresh, prompt
     * improvements in code would never reach existing Super-Agents.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshManagedPrompts() {
        List<AgentSuperAgent> agents;
        try {
            agents = superAgentMapper.selectList(Wrappers.<AgentSuperAgent>lambdaQuery());
        } catch (Exception e) {
            log.warn("Router prompt refresh skipped: {}", e.getMessage());
            return;
        }
        for (AgentSuperAgent agent : agents) {
            try {
                syncPrompts(agent);
            } catch (Exception e) {
                log.warn("Router prompt refresh failed: agent={}, error={}",
                        agent.getId(), e.getMessage());
            }
        }
    }

    /** Role / scope / position edits must be reflected in both managed instances' prompts. */
    private void syncPrompts(AgentSuperAgent agent) {
        if (AgentType.LOCAL.equals(agent.getAgentType())) {
            return;
        }
        if (agent.getPositionNameEn() == null || agent.getPositionNameEn().isBlank()) {
            return;
        }
        updateInstancePrompt(agent.getRouterInstanceId(), SuperAgentPrompt.routerPrompt(
                agent.getPositionNameEn(), agent.getDescription(),
                agentRouterPromptService.resolve()));
        updateInstancePrompt(agent.getWorkerInstanceId(), SuperAgentPrompt.workerPrompt(
                agent.getPositionNameEn(), agent.getDescription()));
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
        // Targeted update: a full updateInstance would reset status/completionProtocol/config.
        instanceSpi.updateSystemPrompt(instanceId, systemPrompt);
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
        vo.setAgentType(agent.getAgentType());
        vo.setLocalRuntimeId(agent.getLocalRuntimeId());
        vo.setName(agent.getName());
        vo.setDescription(agent.getDescription());
        vo.setPublicVisible(Boolean.TRUE.equals(agent.getPublicVisible()));
        vo.setRouterInstanceId(agent.getRouterInstanceId());
        vo.setWorkerInstanceId(agent.getWorkerInstanceId());
        vo.setPositionCode(agent.getPositionCode());
        vo.setPositionNameCn(agent.getPositionNameCn());
        vo.setPositionNameEn(agent.getPositionNameEn());
        vo.setConfig(agent.getConfig());
        vo.setStatus(agent.getStatus());
        vo.setCreatedAt(agent.getCreatedAt() == null ? null : agent.getCreatedAt().format(DTF));
        return vo;
    }

    private static String normalizeAgentType(String value) {
        String type = value == null || value.isBlank()
                ? AgentType.CLOUD : value.trim().toUpperCase(Locale.ROOT);
        if (!AgentType.CLOUD.equals(type) && !AgentType.LOCAL.equals(type)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "agentType must be CLOUD or LOCAL");
        }
        return type;
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
