package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouteListBuilder {

    private final RouteSpi routeSpi;
    private final InstanceSpi instanceSpi;
    private final ModelProviderSpi modelProviderSpi;

    public List<ModelRouteFullVO> fromContext(KernelContext ctx) {
        List<ModelRouteFullVO> routes = new ArrayList<>();
        if (ctx != null) {
            if (ctx.getModelRoute() != null) routes.add(ctx.getModelRoute());
            if (ctx.getFallbackRoutes() != null) routes.addAll(ctx.getFallbackRoutes());
        }
        return routes;
    }

    public List<ModelRouteFullVO> fromInstance(Long agentInstanceId) {
        List<ModelRouteFullVO> routes = new ArrayList<>();
        if (agentInstanceId == null) return routes;
        try {
            InstanceVO instance = instanceSpi.getInstance(agentInstanceId);
            if (instance == null || instance.getModelRouteId() == null) return routes;
            routes.addAll(fromRouteId(instance.getModelRouteId()));
        } catch (Exception e) {
            log.warn("Failed to resolve routes for instance {}", agentInstanceId, e);
        }
        return routes;
    }

    private List<ModelRouteFullVO> fromRouteId(Long modelRouteId) {
        List<ModelRouteFullVO> routes = new ArrayList<>();
        ModelRouteVO primary = routeSpi.getRoute(modelRouteId);
        if (primary != null) {
            routes.add(toFull(primary));
            if (primary.getFallbackIds() != null && !primary.getFallbackIds().isBlank()) {
                for (String idStr : primary.getFallbackIds().split(",")) {
                    try {
                        Long fallbackId = Long.parseLong(idStr.trim());
                        ModelRouteVO fb = routeSpi.getRoute(fallbackId);
                        if (fb != null) routes.add(toFull(fb));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return routes;
    }

    private ModelRouteFullVO toFull(ModelRouteVO route) {
        ModelRouteFullVO full = new ModelRouteFullVO();
        full.setId(route.getId());
        full.setProviderId(route.getProviderId());
        full.setModelName(route.getModelName());
        full.setWeight(route.getWeight());
        full.setFallbackIds(route.getFallbackIds());
        full.setStatus(route.getStatus());
        full.setMaxInputTokens(route.getMaxInputTokens());
        full.setMaxOutputTokens(route.getMaxOutputTokens());
        full.setCompany(route.getCompany());
        if (route.getProviderId() != null) {
            try {
                ModelProviderVO provider = modelProviderSpi.getProvider(route.getProviderId());
                if (provider != null) {
                    full.setProviderName(provider.getName());
                    full.setBaseUrl(provider.getBaseUrl());
                    full.setApiKeyId(provider.getApiKeyId());
                }
            } catch (Exception ignored) {}
        }
        return full;
    }
}
