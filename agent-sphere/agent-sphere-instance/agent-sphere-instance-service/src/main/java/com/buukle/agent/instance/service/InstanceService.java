package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;

public interface InstanceService extends IService<AgentInstance>, InstanceSpi {
    InstanceVO createInstance(CreateInstanceDTO dto);
    InstanceVO setModelRoute(Long id, Long modelRouteId);
}
