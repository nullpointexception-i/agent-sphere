package com.buukle.agent.model.spi;

import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;

import java.util.List;

public interface RouteSpi {
    ModelRouteVO createRoute(CreateRouteDTO dto);

    ModelRouteVO getRoute(Long id);

    /** Owner-scoped default route, or null when not configured. */
    ModelRouteVO getDefaultRoute();

    List<ModelRouteVO> listRoutesByProvider(Long providerId, String keyword);

    ModelRouteVO updateRoute(Long id, CreateRouteDTO dto);

    void deleteRoute(Long id);

    List<ModelRouteVO> listAllRoutes(String keyword);

    /** The tenant's system route (the one auto-created with the tenant), or null. */
    ModelRouteVO findSystemRoute(Long tenantId);

    /** Marks a route as its tenant's system route (group router target). */
    void markSystemRoute(Long id);

    /** System routes visible to the caller; a platform admin sees every tenant. */
    List<ModelRouteVO> listSystemRoutes();
}
