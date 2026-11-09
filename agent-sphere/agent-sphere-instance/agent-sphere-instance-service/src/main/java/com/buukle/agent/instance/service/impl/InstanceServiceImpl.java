package com.buukle.agent.instance.service.impl;


import com.buukle.agent.instance.dtvo.enums.InstanceRole;import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.event.InstanceDeletedEvent;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.AvatarGenerator;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceEnum;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.InstanceMapper;
import com.buukle.agent.instance.service.InstanceService;
import com.buukle.agent.instance.service.converter.InstanceConverter;
import com.buukle.agent.model.spi.RouteSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceServiceImpl extends ServiceImpl<InstanceMapper, AgentInstance> implements InstanceService {
    private final InstanceConverter instanceConverter;
    private final RouteSpi routeSpi;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public InstanceVO createInstance(CreateInstanceDTO dto) {
        if (dto.getImage() == null || dto.getImage().isBlank()) {
            dto.setImage(AvatarGenerator.generateBase64());
        } else {
            byte[] decoded = java.util.Base64.getDecoder().decode(dto.getImage().contains(",") ? dto.getImage().split(",")[1] : dto.getImage());
            if (decoded.length > 2 * 1024 * 1024) {
                throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "Image too large, max 2MB");
            }
        }
        AgentInstance instance = instanceConverter.toDO(dto);
        save(instance);
        return instanceConverter.toVO(instance);
    }

    @Override
    public InstanceVO updateInstance(Long id, CreateInstanceDTO dto) {
        requireOwnership(id);
        AgentInstance existing = getById(id);
        if (existing == null) {
            throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(existing.getSystemManaged())) {
            // System instances are editable for their route/prompt; role, ownership and
            // lifecycle are still forced from the stored row below.
            log.info("Updating system-managed instance: id={}", id);
        }
        if (dto.getImage() != null && !dto.getImage().isBlank()) {
            byte[] decoded = java.util.Base64.getDecoder().decode(dto.getImage().contains(",") ? dto.getImage().split(",")[1] : dto.getImage());
            if (decoded.length > 2 * 1024 * 1024) {
                throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "Image too large, max 2MB");
            }
        }
        boolean clearLoop = dto.getMaxLoopCount() != null && dto.getMaxLoopCount() == 0;
        // MODEL_DEFAULT / blank = clear the instance override and follow the model route again.
        boolean clearCompletionProtocol = dto.getCompletionProtocol() != null
                && CompletionProtocol.isInherit(dto.getCompletionProtocol());
        AgentInstance instance = instanceConverter.toDO(dto);
        instance.setId(id);
        // Role, system ownership and lifecycle status are managed by the Super-Agent lifecycle,
        // not by the generic instance edit form (which does not submit these fields).
        instance.setInstanceRole(existing.getInstanceRole());
        instance.setSystemManaged(existing.getSystemManaged());
        instance.setStatus(existing.getStatus());
        updateById(instance);
        if (clearLoop) {
            // 0 = clear the override (set NULL); updateById ignores null fields, so an explicit set is required
            lambdaUpdate()
                    .eq(AgentInstance::getId, id)
                    .set(AgentInstance::getMaxLoopCount, null)
                    .update();
        }
        if (clearCompletionProtocol) {
            // updateById ignores null fields, so clearing the override needs an explicit SET NULL.
            lambdaUpdate()
                    .eq(AgentInstance::getId, id)
                    .set(AgentInstance::getCompletionProtocol, null)
                    .update();
        }
        if (dto.getConfig() != null && dto.getConfig().isBlank()) {
            // Empty string = clear the config override (set NULL to use the global fallback)
            lambdaUpdate()
                    .eq(AgentInstance::getId, id)
                    .set(AgentInstance::getConfig, null)
                    .update();
        }
        AgentInstance saved = getById(id);
        return instanceConverter.toVO(saved);
    }

    @Override
    public void updateSystemPrompt(Long id, String systemPrompt) {
        if (id == null || systemPrompt == null) {
            return;
        }
        // Internal system maintenance (startup prompt refresh): no request context/owner here.
        lambdaUpdate()
                .eq(AgentInstance::getId, id)
                .set(AgentInstance::getSystemPrompt, systemPrompt)
                .update();
    }

    @Override
    @Transactional
    public void deleteInstance(Long id) {
        requireOwnership(id);
        removeById(id);
        eventPublisher.publishEvent(new InstanceDeletedEvent(id));
    }

    @Override
    @Transactional
    public void batchDeleteInstances(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids) {
            requireOwnership(id);
        }
        removeByIds(ids);
        for (Long id : ids) {
            eventPublisher.publishEvent(new InstanceDeletedEvent(id));
        }
    }

    private void requireOwnership(Long id) {
        if (com.buukle.agent.common.context.AuthContext.isSuperAdmin()) {
            return;
        }
        AgentInstance existing = getById(id);
        if (existing == null) {
            throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        }
        String owner = com.buukle.agent.common.context.AuthContext.getUsername();
        if (!java.util.Objects.equals(owner, existing.getCreatedBy())) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.FORBIDDEN, "Cannot operate on another user's instance");
        }
    }

    @Override
    public InstanceVO getInstance(Long id) {
        AgentInstance instance = getById(id);
        if (instance == null) throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        return instanceConverter.toVO(instance);
    }

    @Override
    public InstanceVO findByRole(String instanceRole) {
        if (instanceRole == null || instanceRole.isBlank()) {
            return null;
        }
        AgentInstance instance = lambdaQuery()
                .eq(AgentInstance::getInstanceRole, instanceRole.toUpperCase())
                .eq(AgentInstance::getSystemManaged, true)
                .eq(AgentInstance::getStatus, InstanceEnum.STATUS_ENABLED)
                .orderByAsc(AgentInstance::getId)
                .last("LIMIT 1")
                .one();
        return instance == null ? null : instanceConverter.toVO(instance);
    }

    @Override
    public List<InstanceVO> listInstances(String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        log.info("listInstances called: keyword='{}', startTime={}, endTime={}", keyword, startTime, endTime);
        List<AgentInstance> instances = lambdaQuery()
                .eq(AgentInstance::getInstanceRole, InstanceRole.CHAT.value())
                .like(keyword != null && !keyword.isBlank(), AgentInstance::getName, keyword)
                .ge(startTime != null, AgentInstance::getCreatedAt, startTime)
                .le(endTime != null, AgentInstance::getCreatedAt, endTime)
                .orderByDesc(AgentInstance::getCreatedAt)
                .list();
        log.info("listInstances result: {} rows", instances.size());
        return instances.stream().map(instanceConverter::toVO).toList();
    }

    @Override
    public IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        return pageInstances(page, size, keyword, startTime, endTime, null);
    }

    @Override
    public IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime,
                                           LocalDateTime endTime, String instanceRole) {
        return pageInstances(page, size, keyword, startTime, endTime, instanceRole, null);
    }

    @Override
    public IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime,
                                           LocalDateTime endTime, String instanceRole, String scope) {
        boolean systemScope = "SYSTEM".equalsIgnoreCase(scope);
        String requestedRole = instanceRole == null || instanceRole.isBlank()
                ? (systemScope ? "ALL" : InstanceRole.CHAT.value()) : instanceRole.trim().toUpperCase();
        boolean allRoles = "ALL".equals(requestedRole);
        String role = allRoles ? null : InstanceRole.normalize(requestedRole);
        Page<AgentInstance> p = lambdaQuery()
                .eq(!allRoles, AgentInstance::getInstanceRole, role)
                .eq(systemScope, AgentInstance::getSystemManaged, true)
                .and(!systemScope, w -> w.isNull(AgentInstance::getSystemManaged)
                        .or().eq(AgentInstance::getSystemManaged, false))
                .like(keyword != null && !keyword.isBlank(), AgentInstance::getName, keyword)
                .ge(startTime != null, AgentInstance::getCreatedAt, startTime)
                .le(endTime != null, AgentInstance::getCreatedAt, endTime)
                .orderByDesc(AgentInstance::getCreatedAt)
                .page(new Page<>(page, size));
        return p.convert(instanceConverter::toVO);
    }

    @Override
    public InstanceVO findSystemByTenant(Long tenantId, String instanceRole) {
        if (tenantId == null || instanceRole == null || instanceRole.isBlank()) {
            return null;
        }
        Long previousTenant = TenantContext.get();
        TenantScope previousScope = TenantScopeHolder.get();
        try {
            TenantContext.set(tenantId);
            TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, "system"));
            AgentInstance instance = lambdaQuery()
                    .eq(AgentInstance::getTenantId, tenantId)
                    .eq(AgentInstance::getInstanceRole, instanceRole.toUpperCase())
                    .eq(AgentInstance::getSystemManaged, true)
                    .orderByAsc(AgentInstance::getId)
                    .last("LIMIT 1")
                    .one();
            return instance == null ? null : instanceConverter.toVO(instance);
        } finally {
            TenantScopeHolder.clear();
            TenantContext.clear();
            if (previousScope != null) {
                TenantScopeHolder.start(previousScope);
            }
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            }
        }
    }

    @Override
    public InstanceVO setModelRoute(Long id, Long modelRouteId) {
        AgentInstance instance = getById(id);
        if (instance == null) throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        if (modelRouteId != null) {
            var route = routeSpi.getRoute(modelRouteId);
            if (route.getApiKeyConfigured() != null && !route.getApiKeyConfigured()) {
                throw new BizException(InstanceErrorCode.ROUTE_NO_API_KEY);
            }
        }
        instance.setModelRouteId(modelRouteId);
        updateById(instance);
        return instanceConverter.toVO(instance);
    }

    @Override
    public List<InstanceVO> listLatestNInstances(int n) {
        int limit = Math.min(Math.max(n, 1), 32);
        List<AgentInstance> instances = lambdaQuery()
                .eq(AgentInstance::getInstanceRole, InstanceRole.CHAT.value())
                .orderByDesc(AgentInstance::getCreatedAt)
                .last("LIMIT " + limit)
                .list();
        return instances.stream().map(instanceConverter::toVO).toList();
    }
}
