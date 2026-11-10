package com.buukle.agent.model.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.spi.ModelProviderSpi;

public interface ModelProviderService extends IService<AgentModelProvider>, ModelProviderSpi {

    IPage<ModelProviderVO> pageProviders(int page, int size, String keyword);

    /** @param scope USER (creator-only) or TENANT (tenant-level resources). */
    IPage<ModelProviderVO> pageProviders(int page, int size, String keyword, String scope);
}
