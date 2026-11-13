package com.buukle.agent.admin.service.impl;

import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.dtvo.dto.TenantResourceTemplateItemDTO;
import com.buukle.agent.admin.dtvo.enums.TenantResourceTemplateType;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.constant.OwnerScope;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceRole;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates a tenant's default resources from {@code tenant.resource-template}.
 *
 * <p>Unlike user provisioning, every created resource is tenant-owned
 * ({@code owner_scope = TENANT}): tenant administrators can see and maintain them,
 * and ordinary users cannot. No API key is created here; the tenant administrator
 * fills it in later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantResourceProvisioner {

    private static final long DEFAULT_MAX_INPUT_TOKENS = 128000L;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 4096L;

    private final SystemConfigSpi systemConfigSpi;
    private final ModelProviderSpi modelProviderSpi;
    private final RouteSpi routeSpi;
    private final InstanceSpi instanceSpi;

    /**
     * @return the GROUP_ROUTER instance that should become the tenant's current instance, or null
     */
    public Long provision(SysTenant tenant, String operator) {
        return ensure(tenant, operator);
    }

    /**
     * Idempotently ensures the tenant's default resources exist.
     *
     * <p>Existing tenant-owned providers/routes/instances are reused, so the default tenant can be
     * checked on every boot without creating duplicates.
     *
     * @return the GROUP_ROUTER instance that should become the tenant's current instance, or null
     */
    public Long ensure(SysTenant tenant, String operator) {
        if (tenant == null || tenant.getId() == null) {
            return null;
        }
        String template = systemConfigSpi.get(SystemConfigKeys.TENANT_RESOURCE_TEMPLATE, "");
        if (!StringUtils.hasText(template)) {
            return null;
        }
        Long previousTenant = TenantContext.get();
        TenantScope previousScope = TenantScopeHolder.get();
        try {
            TenantContext.set(tenant.getId());
            TenantScopeHolder.start(TenantScope.tenantOnly(tenant.getId(), operator));
            return initialize(template);
        } catch (Exception e) {
            log.error("Tenant resource provisioning failed: tenant={}", tenant.getId(), e);
            throw e instanceof BizException biz
                    ? biz
                    : new BizException(CommonErrorCode.PARAM_INVALID,
                            "Tenant resource template failed: " + e.getMessage());
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

    private Long initialize(String template) throws Exception {
        List<TenantResourceTemplateItemDTO> items = JsonUtils.parse(
                template, new TypeReference<List<TenantResourceTemplateItemDTO>>() {
                });
        if (items == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "tenant.resource-template must be a JSON array");
        }
        Map<String, Long> providers = new HashMap<>();
        Map<String, Long> routes = new HashMap<>();
        Long currentInstanceId = null;

        for (TenantResourceTemplateItemDTO item : items) {
            if (item.getType() == TenantResourceTemplateType.MODEL_PROVIDER) {
                ensureProvider(item, providers);
            }
        }
        for (TenantResourceTemplateItemDTO item : items) {
            if (item.getType() == TenantResourceTemplateType.MODEL_ROUTE) {
                ensureRoute(item, providers, routes);
            }
        }
        for (TenantResourceTemplateItemDTO item : items) {
            if (item.getType() == TenantResourceTemplateType.INSTANCE) {
                Long instanceId = ensureInstance(item, routes);
                if (instanceId != null && currentInstanceId == null
                        && InstanceRole.GROUP_ROUTER.value().equalsIgnoreCase(instanceRole(item))) {
                    currentInstanceId = instanceId;
                }
            }
        }
        if (currentInstanceId == null) {
            // Legacy tenants may already have the router but no current-instance pointer.
            InstanceVO existing = instanceSpi.findByRole(InstanceRole.GROUP_ROUTER.value());
            if (existing != null) {
                currentInstanceId = existing.getId();
            }
        }
        // Every tenant gets exactly one project assistant, even when the template predates it.
        instanceSpi.ensureProjectRouter(TenantContext.get());
        return currentInstanceId;
    }

    private void ensureProvider(TenantResourceTemplateItemDTO item, Map<String, Long> providers) {
        String name = item.getName();
        if (!StringUtils.hasText(name)) {
            return;
        }
        Long existing = findTenantProvider(name);
        if (existing != null) {
            providers.put(name, existing);
            return;
        }
        createProvider(item, providers);
    }

    private Long findTenantProvider(String name) {
        List<ModelProviderVO> existing = modelProviderSpi.listProviders(name);
        if (existing == null) {
            return null;
        }
        return existing.stream()
                .filter(provider -> name.equals(provider.getName()))
                .filter(provider -> OwnerScope.isTenant(provider.getOwnerScope()))
                .map(ModelProviderVO::getId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void createProvider(TenantResourceTemplateItemDTO item, Map<String, Long> providers) {
        String name = item.getName();
        if (!StringUtils.hasText(name)) {
            return;
        }
        CreateModelProviderDTO dto = new CreateModelProviderDTO();
        dto.setName(name);
        dto.setBaseUrl(item.getBaseUrl());
        dto.setConfig(item.getConfig());
        dto.setOwnerScope(OwnerScope.TENANT);
        ModelProviderVO provider = modelProviderSpi.createProvider(dto);
        providers.put(name, provider.getId());
    }

    private void ensureRoute(TenantResourceTemplateItemDTO item, Map<String, Long> providers,
                             Map<String, Long> routes) {
        Long providerId = providers.get(item.getProvider());
        String modelName = item.getModelName();
        if (providerId == null || !StringUtils.hasText(modelName)) {
            return;
        }
        Long existing = findTenantRoute(providerId, modelName);
        if (existing != null) {
            routes.put(modelName, existing);
            return;
        }
        createRoute(item, providers, routes);
    }

    private Long findTenantRoute(Long providerId, String modelName) {
        List<ModelRouteVO> existing = routeSpi.listRoutesByProvider(providerId, null);
        if (existing == null) {
            return null;
        }
        return existing.stream()
                .filter(route -> modelName.equals(route.getModelName()))
                .map(ModelRouteVO::getId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void createRoute(TenantResourceTemplateItemDTO item, Map<String, Long> providers,
                             Map<String, Long> routes) {
        Long providerId = providers.get(item.getProvider());
        String modelName = item.getModelName();
        if (providerId == null || !StringUtils.hasText(modelName)) {
            return;
        }
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(providerId);
        dto.setModelName(modelName);
        dto.setMaxInputTokens(item.getMaxInputTokens() == null
                ? DEFAULT_MAX_INPUT_TOKENS : item.getMaxInputTokens());
        dto.setMaxOutputTokens(item.getMaxOutputTokens() == null
                ? DEFAULT_MAX_OUTPUT_TOKENS : item.getMaxOutputTokens());
        dto.setIsDefault(Boolean.TRUE.equals(item.getIsDefault()));
        dto.setSupportsAttachment(Boolean.TRUE.equals(item.getSupportsAttachment()));
        dto.setCompany(item.getCompany());
        dto.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        ModelRouteVO route = routeSpi.createRoute(dto);
        routes.put(modelName, route.getId());
    }

    private Long ensureInstance(TenantResourceTemplateItemDTO item, Map<String, Long> routes) {
        String name = item.getName();
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String role = instanceRole(item);
        Long existing = findTenantInstance(name, role);
        if (existing != null) {
            return existing;
        }
        return createInstance(item, routes);
    }

    private Long findTenantInstance(String name, String role) {
        if (InstanceRole.GROUP_ROUTER.value().equalsIgnoreCase(role)) {
            // A tenant may have exactly one GROUP_ROUTER; reuse it even if its name was edited.
            InstanceVO current = instanceSpi.findByRole(role);
            if (current != null) {
                return current.getId();
            }
        }
        var page = instanceSpi.pageInstances(
                1, 100, name, null, null, role, OwnerScope.TENANT);
        if (page == null || page.getRecords() == null) {
            return null;
        }
        return page.getRecords().stream()
                .filter(instance -> name.equals(instance.getName()))
                .map(InstanceVO::getId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Long createInstance(TenantResourceTemplateItemDTO item, Map<String, Long> routes) {
        String name = item.getName();
        if (!StringUtils.hasText(name)) {
            return null;
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName(name);
        dto.setDescription(item.getDescription());
        dto.setSystemPrompt(item.getSystemPrompt());
        dto.setBusinessType(item.getBusinessType());
        dto.setInstanceRole(instanceRole(item));
        dto.setOwnerScope(OwnerScope.TENANT);
        dto.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        dto.setAllowConsoleSession(Boolean.TRUE.equals(item.getAllowConsoleSession()));
        dto.setAllowWidgetSession(Boolean.TRUE.equals(item.getAllowWidgetSession()));
        Long routeId = routes.get(item.getRoute());
        if (routeId == null && !routes.isEmpty()) {
            routeId = routes.values().iterator().next();
        }
        dto.setModelRouteId(routeId);
        InstanceVO instance = instanceSpi.createInstance(dto);
        return instance == null ? null : instance.getId();
    }

    private static String instanceRole(TenantResourceTemplateItemDTO item) {
        return StringUtils.hasText(item.getInstanceRole())
                ? item.getInstanceRole() : InstanceRole.GROUP_ROUTER.value();
    }
}
