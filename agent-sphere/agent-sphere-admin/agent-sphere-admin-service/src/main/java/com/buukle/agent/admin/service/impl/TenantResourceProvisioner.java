package com.buukle.agent.admin.service.impl;

import com.buukle.agent.admin.domain.SysTenant;
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
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
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

    private final SystemConfigSpi systemConfigSpi;
    private final ModelProviderSpi modelProviderSpi;
    private final RouteSpi routeSpi;
    private final InstanceSpi instanceSpi;

    /**
     * @return the GROUP_ROUTER instance that should become the tenant's current instance, or null
     */
    public Long provision(SysTenant tenant, String operator) {
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
        JsonNode array = JsonUtils.getMapper().readTree(template);
        if (array == null || !array.isArray()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "tenant.resource-template must be a JSON array");
        }
        Map<String, Long> providers = new HashMap<>();
        Map<String, Long> routes = new HashMap<>();
        Long currentInstanceId = null;

        for (JsonNode item : array) {
            if ("model_provider".equals(item.path("type").asText(""))) {
                createProvider(item, providers);
            }
        }
        for (JsonNode item : array) {
            if ("model_route".equals(item.path("type").asText(""))) {
                createRoute(item, providers, routes);
            }
        }
        for (JsonNode item : array) {
            if ("instance".equals(item.path("type").asText(""))) {
                Long instanceId = createInstance(item, routes);
                if (instanceId != null && currentInstanceId == null
                        && InstanceRole.GROUP_ROUTER.value().equalsIgnoreCase(
                                item.path("instanceRole").asText(""))) {
                    currentInstanceId = instanceId;
                }
            }
        }
        // Every tenant gets exactly one project assistant, even when the template predates it.
        instanceSpi.ensureProjectRouter(TenantContext.get());
        return currentInstanceId;
    }

    private void createProvider(JsonNode item, Map<String, Long> providers) {
        String name = item.path("name").asText("");
        if (!StringUtils.hasText(name)) {
            return;
        }
        CreateModelProviderDTO dto = new CreateModelProviderDTO();
        dto.setName(name);
        dto.setBaseUrl(item.path("baseUrl").asText(null));
        dto.setConfig(item.hasNonNull("config") ? item.path("config").toString() : null);
        dto.setOwnerScope(OwnerScope.TENANT);
        ModelProviderVO provider = modelProviderSpi.createProvider(dto);
        providers.put(name, provider.getId());
    }

    private void createRoute(JsonNode item, Map<String, Long> providers, Map<String, Long> routes) {
        String providerName = item.path("provider").asText("");
        Long providerId = providers.get(providerName);
        String modelName = item.path("modelName").asText("");
        if (providerId == null || !StringUtils.hasText(modelName)) {
            return;
        }
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(providerId);
        dto.setModelName(modelName);
        dto.setMaxInputTokens(item.path("maxInputTokens").asLong(128000));
        dto.setMaxOutputTokens(item.path("maxOutputTokens").asLong(4096));
        dto.setIsDefault(item.path("isDefault").asBoolean(false));
        dto.setSupportsAttachment(item.path("supportsAttachment").asBoolean(false));
        dto.setCompany(item.path("company").asText(null));
        dto.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        ModelRouteVO route = routeSpi.createRoute(dto);
        routes.put(modelName, route.getId());
    }

    private Long createInstance(JsonNode item, Map<String, Long> routes) {
        String name = item.path("name").asText("");
        if (!StringUtils.hasText(name)) {
            return null;
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName(name);
        dto.setDescription(item.path("description").asText(null));
        dto.setSystemPrompt(item.path("systemPrompt").asText(null));
        dto.setBusinessType(item.path("businessType").asText(null));
        dto.setInstanceRole(item.path("instanceRole").asText(InstanceRole.GROUP_ROUTER.value()));
        dto.setOwnerScope(OwnerScope.TENANT);
        dto.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        dto.setAllowConsoleSession(item.path("allowConsoleSession").asBoolean(false));
        dto.setAllowWidgetSession(item.path("allowWidgetSession").asBoolean(false));
        Long routeId = routes.get(item.path("route").asText(""));
        if (routeId == null && !routes.isEmpty()) {
            routeId = routes.values().iterator().next();
        }
        dto.setModelRouteId(routeId);
        InstanceVO instance = instanceSpi.createInstance(dto);
        return instance == null ? null : instance.getId();
    }
}
