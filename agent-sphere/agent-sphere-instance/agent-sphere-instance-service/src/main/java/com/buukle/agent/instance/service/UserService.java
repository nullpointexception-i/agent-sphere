package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.spi.UserSpi;

public interface UserService extends IService<AgentUser>, UserSpi {
}
