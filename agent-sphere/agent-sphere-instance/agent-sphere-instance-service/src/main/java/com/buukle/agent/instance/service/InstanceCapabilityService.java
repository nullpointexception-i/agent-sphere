package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentInstanceCapability;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;

public interface InstanceCapabilityService extends IService<AgentInstanceCapability>, InstanceCapabilitySpi {
}
