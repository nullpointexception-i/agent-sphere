package com.buukle.agent.model.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.model.domain.AgentModelRoute;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;

public interface RouteService extends IService<AgentModelRoute>, RouteSpi {

    /** Make this route the owner's default, clearing any previous default. */
    ModelRouteVO setDefault(Long id);
}
