package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentMemory;
import com.buukle.agent.instance.spi.MemorySpi;

public interface MemoryService extends IService<AgentMemory>, MemorySpi {
}
