package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SuperAgentDirectoryServiceImpl implements SuperAgentDirectorySpi {

    private final AgentSuperAgentMapper superAgentMapper;

    @Override
    public ChatAgentIdentity resolveForChat(Long agentId, Long userId) {
        if (agentId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "agentId is required");
        }
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        AgentSuperAgent agent = superAgentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin() && !Objects.equals(userId, agent.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return new ChatAgentIdentity(agent.getId(), agent.getName(),
                agent.getOwnerUserId(), agent.getStatus());
    }
}
