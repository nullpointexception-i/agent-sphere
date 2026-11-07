package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.domain.vo.RunUsageVO;
import com.buukle.agent.instance.spi.RunSpi;

public interface RunService extends IService<AgentRun>, RunSpi {

    /** Single-run usage aggregation (InteractionModal header / operations queries). */
    RunUsageVO usageSummary(Long runId);
}
