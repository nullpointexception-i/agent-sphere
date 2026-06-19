package com.buukle.agent.capability.cli.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.capability.cli.domain.CapabilityCli;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;

public interface CapabilityCliService extends IService<CapabilityCli>, CapabilityCliSpi {
}
