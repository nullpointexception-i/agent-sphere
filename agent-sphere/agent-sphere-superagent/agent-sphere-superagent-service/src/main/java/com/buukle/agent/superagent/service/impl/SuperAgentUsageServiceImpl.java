package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.vo.SuperAgentUsageVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.SuperAgentUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SuperAgentUsageServiceImpl implements SuperAgentUsageService {

    private static final int MAX_DAYS = 90;

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;
    private final AgentSuperAgentMapper superAgentMapper;

    @Override
    public List<SuperAgentUsageVO> usage(Long superAgentId, int days) {
        assertOwned(superAgentId);
        int window = Math.min(MAX_DAYS, Math.max(1, days));
        return interactionRecordSpi.usageBySuperAgent(superAgentId, window);
    }

    private void assertOwned(Long superAgentId) {
        AgentSuperAgent agent = superAgentMapper.selectById(superAgentId);
        if (agent == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin() && !AuthContext.getUserId().equals(agent.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
