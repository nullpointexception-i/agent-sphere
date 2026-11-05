package com.buukle.agent.superagent.service;

import com.buukle.agent.instance.domain.vo.SuperAgentUsageVO;

import java.util.List;

/** Usage and cost attribution queries for one Super-Agent. */
public interface SuperAgentUsageService {

    /**
     * Daily token usage grouped by session type.
     *
     * @param days rolling window size, clamped to [1, 90]
     */
    List<SuperAgentUsageVO> usage(Long superAgentId, int days);
}
