package com.buukle.agent.model.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.model.domain.AgentApiKey;
import com.buukle.agent.model.spi.ApiKeySpi;

public interface ApiKeyService extends IService<AgentApiKey>, ApiKeySpi {
}
