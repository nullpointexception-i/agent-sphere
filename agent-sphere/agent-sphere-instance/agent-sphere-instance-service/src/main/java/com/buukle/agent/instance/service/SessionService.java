package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentSession;
import com.buukle.agent.instance.spi.SessionSpi;

public interface SessionService extends IService<AgentSession>, SessionSpi {
}
