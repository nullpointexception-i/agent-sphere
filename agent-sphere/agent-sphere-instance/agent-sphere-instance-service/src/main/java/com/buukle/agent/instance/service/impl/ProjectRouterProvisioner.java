package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.constant.OwnerScope;
import com.buukle.agent.common.constant.ProjectRouterPrompt;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceRole;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.repository.InstanceMapper;
import com.buukle.agent.instance.service.converter.InstanceConverter;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Idempotent provisioning of the tenant-level Project Assistant instance.
 *
 * <p>V191 seeds existing tenants; this component covers new tenants and repairs a missing
 * instance or missing project-tool bindings at first use.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRouterProvisioner {

    private static final String INSTANCE_NAME = "Project Assistant";
    private static final String INSTANCE_DESCRIPTION = "Tenant-level assistant for the project space";
    private static final String SYSTEM_OPERATOR = "system";
    private static final List<Integer> PROJECT_TOOL_IDS = List.of(
            BuiltinToolEnum.PROJECT_QUERY.getId(),
            BuiltinToolEnum.PROJECT_MUTATE.getId(),
            BuiltinToolEnum.PROJECT_AVATAR.getId(),
            BuiltinToolEnum.PROJECT_INSIGHT.getId());

    private final InstanceMapper instanceMapper;
    private final InstanceCapabilitySpi capabilitySpi;
    private final RouteSpi routeSpi;
    private final InstanceConverter instanceConverter;

    /**
     * Ensure the tenant has exactly one PROJECT_ROUTER with the project tools bound.
     *
     * @return the effective instance, or {@code null} when no model route is configured yet
     */
    public InstanceVO ensure(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        com.buukle.agent.common.context.TenantScope previous =
                com.buukle.agent.common.context.TenantScopeHolder.get();
        try {
            // Tenant-owned instances/bindings may have been created by 'system' or another admin;
            // a self-only request scope would hide them and attempt duplicate inserts.
            com.buukle.agent.common.context.TenantScopeHolder.start(
                    com.buukle.agent.common.context.TenantScope.tenantOnly(tenantId, null));
            AgentInstance instance = find(tenantId);
            if (instance == null) {
                Long modelRouteId = resolveModelRouteId(tenantId);
                if (modelRouteId == null) {
                    log.warn("Project assistant not provisioned: tenant {} has no model route", tenantId);
                    return null;
                }
                instance = create(tenantId, modelRouteId);
            }
            bindProjectTools(instance);
            return instanceConverter.toVO(instance);
        } finally {
            if (previous == null) {
                com.buukle.agent.common.context.TenantScopeHolder.clear();
            } else {
                com.buukle.agent.common.context.TenantScopeHolder.start(previous);
            }
        }
    }

    public AgentInstance find(Long tenantId) {
        return instanceMapper.selectOne(new LambdaQueryWrapper<AgentInstance>()
                .eq(AgentInstance::getTenantId, tenantId)
                .eq(AgentInstance::getInstanceRole, InstanceRole.PROJECT_ROUTER.value())
                .orderByAsc(AgentInstance::getId)
                .last("LIMIT 1"));
    }

    private AgentInstance create(Long tenantId, Long modelRouteId) {
        AgentInstance instance = new AgentInstance();
        instance.setName(INSTANCE_NAME);
        instance.setDescription(INSTANCE_DESCRIPTION);
        instance.setTenantId(tenantId);
        instance.setInstanceRole(InstanceRole.PROJECT_ROUTER.value());
        instance.setOwnerScope(OwnerScope.TENANT);
        instance.setModelRouteId(modelRouteId);
        instance.setAllowConsoleSession(true);
        instance.setAllowWidgetSession(false);
        instance.setStatus(InstanceEnum.STATUS_ENABLED);
        instance.setCreatedBy(SYSTEM_OPERATOR);
        instance.setUpdatedBy(SYSTEM_OPERATOR);
        instanceMapper.insert(instance);
        log.info("Project assistant provisioned: tenant={}, instance={}", tenantId, instance.getId());
        return instance;
    }

    private Long resolveModelRouteId(Long tenantId) {
        AgentInstance groupRouter = instanceMapper.selectOne(new LambdaQueryWrapper<AgentInstance>()
                .eq(AgentInstance::getTenantId, tenantId)
                .eq(AgentInstance::getInstanceRole, InstanceRole.GROUP_ROUTER.value())
                .isNotNull(AgentInstance::getModelRouteId)
                .orderByAsc(AgentInstance::getId)
                .last("LIMIT 1"));
        if (groupRouter != null) {
            return groupRouter.getModelRouteId();
        }
        ModelRouteVO fallback = routeSpi.getDefaultRoute();
        return fallback == null ? null : fallback.getId();
    }

    private void bindProjectTools(AgentInstance instance) {
        List<CapabilityVO> existing = capabilitySpi.getCapabilitiesByInstance(instance.getId());
        Set<Integer> bound = existing.stream()
                .filter(cap -> InstanceCapabilityEnum.CAPABILITY_TYPE_BUILTIN.equals(cap.getCapabilityType()))
                .map(cap -> cap.getCapabilityId() == null ? -1 : cap.getCapabilityId().intValue())
                .collect(java.util.stream.Collectors.toSet());
        for (Integer toolId : PROJECT_TOOL_IDS) {
            if (bound.contains(toolId)) {
                continue;
            }
            CreateInstanceCapabilityDTO dto = new CreateInstanceCapabilityDTO();
            dto.setInstanceId(instance.getId());
            dto.setCapabilityType(InstanceCapabilityEnum.CAPABILITY_TYPE_BUILTIN);
            dto.setCapabilityId(toolId.longValue());
            dto.setStatus(InstanceCapabilityEnum.STATUS_ENABLED);
            try {
                capabilitySpi.createCapability(dto);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // A system-created binding from a migration may be invisible to this user's
                // self-only data scope; the unique key is the source of truth.
                log.debug("Project tool {} already bound to instance {}",
                        toolId, instance.getId());
            }
        }
    }
}
