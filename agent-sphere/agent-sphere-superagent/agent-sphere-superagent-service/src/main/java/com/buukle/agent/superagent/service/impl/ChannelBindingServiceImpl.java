package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.superagent.domain.AgentChannelBinding;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.AgentChannelBindingStatus;
import com.buukle.agent.superagent.repository.AgentChannelBindingMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.ChannelBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChannelBindingServiceImpl implements ChannelBindingService {

    private final AgentChannelBindingMapper mapper;
    private final AgentSuperAgentMapper superAgentMapper;

    @Override
    public AgentChannelBinding create(AgentChannelBinding binding) {
        assertOwned(binding.getSuperAgentId());
        if (binding.getStatus() == null || binding.getStatus().isBlank()) {
            binding.setStatus(AgentChannelBindingStatus.ACTIVE);
        }
        mapper.insert(binding);
        return binding;
    }

    @Override
    public List<AgentChannelBinding> list(Long superAgentId) {
        assertOwned(superAgentId);
        return mapper.selectList(Wrappers.<AgentChannelBinding>lambdaQuery()
                .eq(AgentChannelBinding::getSuperAgentId, superAgentId)
                .orderByDesc(AgentChannelBinding::getId));
    }

    @Override
    public AgentChannelBinding get(Long id) {
        AgentChannelBinding binding = mapper.selectById(id);
        if (binding == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "channel binding not found");
        }
        return binding;
    }

    @Override
    public AgentChannelBinding updateStatus(Long id, String status) {
        AgentChannelBinding binding = get(id);
        assertOwned(binding.getSuperAgentId());
        String normalized = status == null
                ? AgentChannelBindingStatus.ACTIVE : status.toUpperCase();
        if (!AgentChannelBindingStatus.ACTIVE.equals(normalized)
                && !AgentChannelBindingStatus.DISABLED.equals(normalized)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "invalid status");
        }
        binding.setStatus(normalized);
        mapper.updateById(binding);
        return binding;
    }

    private void assertOwned(Long superAgentId) {
        AgentSuperAgent agent = superAgentMapper.selectById(superAgentId);
        if (agent == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin()
                && (AuthContext.getUserId() == null
                || !AuthContext.getUserId().equals(agent.getOwnerUserId()))) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
