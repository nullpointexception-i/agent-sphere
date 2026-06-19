package com.buukle.agent.model.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.spi.ModelProviderSpi;

public interface ModelProviderService extends IService<AgentModelProvider>, ModelProviderSpi {
}
