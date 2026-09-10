package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.domain.vo.RunUsageVO;
import com.buukle.agent.instance.spi.RunSpi;

public interface RunService extends IService<AgentRun>, RunSpi {

    /** 单 run 用量聚合（InteractionModal 头部 / 运营查询）。 */
    RunUsageVO usageSummary(Long runId);
}
