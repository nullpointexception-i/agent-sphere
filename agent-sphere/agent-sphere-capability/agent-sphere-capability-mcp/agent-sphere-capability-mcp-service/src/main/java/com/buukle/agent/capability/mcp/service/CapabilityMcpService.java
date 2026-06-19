package com.buukle.agent.capability.mcp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;

public interface CapabilityMcpService extends IService<CapabilityMcp>, CapabilityMcpSpi {
}
