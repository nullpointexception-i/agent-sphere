package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.superagent.domain.AgentEntityBinding;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.EntityBindingStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.repository.AgentEntityBindingMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.spi.EntityAgentDirectorySpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityAgentDirectoryServiceImpl implements EntityAgentDirectorySpi {

    private static final String STATUS_ACTIVE = EntityBindingStatus.ACTIVE;

    private final AgentEntityBindingMapper bindingMapper;
    private final AgentSuperAgentMapper agentMapper;

    @Override
    public List<Long> listBoundAgentIds(String entityType, Long entityId, Long userId) {
        List<AgentEntityBinding> bindings = bindings(entityType, entityId);
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<Long> agentIds = bindings.stream()
                .map(AgentEntityBinding::getSuperAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (agentIds.isEmpty()) {
            return List.of();
        }
        Long tenantId = TenantContext.get();
        Set<Long> active = agentMapper.selectBatchIds(agentIds).stream()
                .filter(agent -> SuperAgentStatus.ACTIVE.equals(agent.getStatus()))
                .filter(agent -> tenantId == null || tenantId.equals(agent.getTenantId()))
                .map(AgentSuperAgent::getId)
                .collect(Collectors.toSet());
        return agentIds.stream().filter(active::contains).toList();
    }

    @Override
    public boolean canMention(String entityType, Long entityId, Long agentId, Long userId) {
        if (agentId == null) {
            return false;
        }
        return listBoundAgentIds(entityType, entityId, userId).contains(agentId);
    }

    private List<AgentEntityBinding> bindings(String entityType, Long entityId) {
        Long tenantId = TenantContext.get();
        LambdaQueryWrapper<AgentEntityBinding> wrapper = new LambdaQueryWrapper<AgentEntityBinding>()
                .eq(AgentEntityBinding::getEntityType, entityType)
                .eq(AgentEntityBinding::getEntityId, entityId)
                .eq(AgentEntityBinding::getStatus, STATUS_ACTIVE);
        if (tenantId != null) {
            wrapper.eq(AgentEntityBinding::getTenantId, tenantId);
        }
        return bindingMapper.selectList(wrapper);
    }
}
