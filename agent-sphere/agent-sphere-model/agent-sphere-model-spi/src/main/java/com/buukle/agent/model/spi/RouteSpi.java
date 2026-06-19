package com.buukle.agent.model.spi;

import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import java.util.List;

public interface RouteSpi {
    ModelRouteVO createRoute(CreateRouteDTO dto);
    ModelRouteVO getRoute(Long id);
    List<ModelRouteVO> listRoutesByProvider(Long providerId, String keyword);
    ModelRouteVO updateRoute(Long id, CreateRouteDTO dto);
    void deleteRoute(Long id);
    List<ModelRouteVO> listAllRoutes(String keyword);
}
