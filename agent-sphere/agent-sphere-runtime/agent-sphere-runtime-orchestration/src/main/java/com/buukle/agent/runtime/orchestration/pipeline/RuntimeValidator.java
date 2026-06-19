package com.buukle.agent.runtime.orchestration.pipeline;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.runtime.orchestration.exception.OrchestrationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class RuntimeValidator {

    private final SessionSpi sessionSpi;
    private final InstanceSpi instanceSpi;
    private final RouteSpi routeSpi;
    private final ModelProviderSpi providerSpi;

    public ValidationResult validate(Long sessionId, Long overrideRouteId) {
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null) {
            throw new BizException(OrchestrationErrorCode.SESSION_NOT_FOUND);
        }

        InstanceVO agentInstance;
        if (session.getAgentInstanceId() != null) {
            agentInstance = instanceSpi.getInstance(session.getAgentInstanceId());
        } else {
            agentInstance = null;
        }
        if (agentInstance == null) {
            throw new BizException(OrchestrationErrorCode.INSTANCE_NOT_FOUND);
        }

        ModelRouteVO primaryRoute = resolvePrimaryRoute(agentInstance, overrideRouteId);
        if (primaryRoute == null) {
            throw new BizException(OrchestrationErrorCode.MODEL_ROUTE_NOT_FOUND);
        }
        if (primaryRoute.getApiKeyConfigured() != null && !primaryRoute.getApiKeyConfigured()) {
            ModelProviderVO provider = providerSpi.getProvider(primaryRoute.getProviderId());
            String name = provider != null ? provider.getName() : String.valueOf(primaryRoute.getProviderId());
            throw new BizException(OrchestrationErrorCode.MODEL_ROUTE_NO_API_KEY,
                "供应商 [" + name + "] 未配置 API 密钥");
        }

        ModelRouteFullVO modelRoute = toFull(primaryRoute);

        // Load fallback routes if defined
        List<ModelRouteFullVO> fallbackRoutes = new ArrayList<>();
        if (primaryRoute.getFallbackIds() != null && !primaryRoute.getFallbackIds().isBlank()) {
            for (String idStr : primaryRoute.getFallbackIds().split(",")) {
                try {
                    Long fbId = Long.parseLong(idStr.trim());
                    ModelRouteVO fbRoute = routeSpi.getRoute(fbId);
                    if (fbRoute != null) {
                        fallbackRoutes.add(toFull(fbRoute));
                    }
                } catch (Exception e) {
                    // skip invalid fallback id
                }
            }
        }

        return ValidationResult.builder()
            .session(session)
            .agentInstance(agentInstance)
            .modelRoute(modelRoute)
            .fallbackRoutes(fallbackRoutes)
            .build();
    }

    private ModelRouteVO resolvePrimaryRoute(InstanceVO agentInstance, Long overrideRouteId) {
        // User-specified override takes priority
        if (overrideRouteId != null) {
            ModelRouteVO route = routeSpi.getRoute(overrideRouteId);
            if (route != null) return route;
        }
        // If instance has a specific modelRouteId, use it directly
        if (agentInstance.getModelRouteId() != null) {
            return routeSpi.getRoute(agentInstance.getModelRouteId());
        }
        // No specific route assigned — pick one by weight from all routes
        List<ModelRouteVO> allRoutes = routeSpi.listRoutesByProvider(null, null);
        if (allRoutes.isEmpty()) return null;
        return weightedSelect(allRoutes);
    }

    private static ModelRouteVO weightedSelect(List<ModelRouteVO> routes) {
        int totalWeight = routes.stream().mapToInt(r -> r.getWeight() != null ? r.getWeight() : 100).sum();
        if (totalWeight <= 0) return routes.get(0);
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (ModelRouteVO r : routes) {
            cumulative += r.getWeight() != null ? r.getWeight() : 100;
            if (roll < cumulative) return r;
        }
        return routes.get(routes.size() - 1);
    }

    private ModelRouteFullVO toFull(ModelRouteVO route) {
        ModelRouteFullVO full = new ModelRouteFullVO();
        full.setId(route.getId());
        full.setProviderId(route.getProviderId());
        full.setModelName(route.getModelName());
        full.setWeight(route.getWeight());
        full.setFallbackIds(route.getFallbackIds());
        full.setStatus(route.getStatus());
        full.setCreatedAt(route.getCreatedAt());
        full.setCompany(route.getCompany());

        if (route.getProviderId() != null) {
            try {
                ModelProviderVO provider = providerSpi.getProvider(route.getProviderId());
                if (provider != null) {
                    full.setProviderName(provider.getName());
                    full.setBaseUrl(provider.getBaseUrl());
                    full.setApiKeyId(provider.getApiKeyId());
                    full.setConfig(provider.getConfig());
                }
            } catch (Exception e) {
                // provider resolve is best-effort
            }
        }
        return full;
    }
}
