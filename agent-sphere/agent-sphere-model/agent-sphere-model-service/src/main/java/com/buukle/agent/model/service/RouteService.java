package com.buukle.agent.model.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.model.domain.AgentModelRoute;
import com.buukle.agent.model.spi.RouteSpi;

public interface RouteService extends IService<AgentModelRoute>, RouteSpi {
}
