package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.domain.AgentEntityBinding;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.BindEntityAgentDTO;
import com.buukle.agent.superagent.dtvo.EntityAgentBindingVO;
import com.buukle.agent.superagent.dtvo.enums.EntityBindingStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.repository.AgentEntityBindingMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.EntityBindingService;
import com.buukle.agent.superagent.spi.EntityTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EntityBindingServiceImpl implements EntityBindingService {

    private static final String DEFAULT_BINDING_ROLE = "ASSISTANT";
    private static final Set<String> ENTITY_TYPES = Set.of(
            EntityTypes.APPLICATION, EntityTypes.APPLICATION_SCENE,
            EntityTypes.PROJECT, EntityTypes.REQUIREMENT);

    private final AgentEntityBindingMapper bindingMapper;
    private final AgentSuperAgentMapper agentMapper;

    @Override
    public List<EntityAgentBindingVO> list(String entityType, Long entityId) {
        String type = normalizeType(entityType);
        Long tenantId = TenantContext.get();
        LambdaQueryWrapper<AgentEntityBinding> wrapper = new LambdaQueryWrapper<AgentEntityBinding>()
                .eq(AgentEntityBinding::getEntityType, type)
                .eq(AgentEntityBinding::getEntityId, entityId)
                .eq(AgentEntityBinding::getStatus, EntityBindingStatus.ACTIVE);
        if (tenantId != null) {
            wrapper.eq(AgentEntityBinding::getTenantId, tenantId);
        }
        return bindingMapper.selectList(wrapper).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public EntityAgentBindingVO bind(BindEntityAgentDTO dto) {
        String type = normalizeType(dto.getEntityType());
        Long userId = currentUser();
        AgentSuperAgent agent = agentMapper.selectById(dto.getAgentId());
        if (agent == null || !SuperAgentStatus.ACTIVE.equals(agent.getStatus())) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "agent not found or inactive");
        }
        boolean owner = userId.equals(agent.getOwnerUserId());
        if (!owner && !Boolean.TRUE.equals(agent.getPublicVisible()) && !AuthContext.isSuperAdmin()) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "only the agent owner can bind a private agent");
        }
        AgentEntityBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentEntityBinding>()
                .eq(AgentEntityBinding::getEntityType, type)
                .eq(AgentEntityBinding::getEntityId, dto.getEntityId())
                .eq(AgentEntityBinding::getSuperAgentId, dto.getAgentId())
                .last("limit 1"));
        if (binding == null) {
            binding = new AgentEntityBinding();
            binding.setEntityType(type);
            binding.setEntityId(dto.getEntityId());
            binding.setSuperAgentId(dto.getAgentId());
            binding.setTenantId(TenantContext.get());
        }
        binding.setBindingRole(StringUtils.hasText(dto.getBindingRole())
                ? dto.getBindingRole() : DEFAULT_BINDING_ROLE);
        binding.setStatus(EntityBindingStatus.ACTIVE);
        if (binding.getId() == null) {
            bindingMapper.insert(binding);
        } else {
            bindingMapper.updateById(binding);
        }
        return toVO(binding);
    }

    @Override
    @Transactional
    public void unbind(String entityType, Long entityId, Long agentId) {
        String type = normalizeType(entityType);
        AgentEntityBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentEntityBinding>()
                .eq(AgentEntityBinding::getEntityType, type)
                .eq(AgentEntityBinding::getEntityId, entityId)
                .eq(AgentEntityBinding::getSuperAgentId, agentId)
                .last("limit 1"));
        if (binding != null) {
            bindingMapper.deleteById(binding.getId());
        }
    }

    private EntityAgentBindingVO toVO(AgentEntityBinding binding) {
        EntityAgentBindingVO vo = new EntityAgentBindingVO();
        vo.setId(binding.getId());
        vo.setEntityType(binding.getEntityType());
        vo.setEntityId(binding.getEntityId());
        vo.setAgentId(binding.getSuperAgentId());
        vo.setBindingRole(binding.getBindingRole());
        vo.setStatus(binding.getStatus());
        vo.setCreatedAt(binding.getCreatedAt());
        AgentSuperAgent agent = agentMapper.selectById(binding.getSuperAgentId());
        if (agent != null) {
            vo.setAgentName(agent.getName());
            vo.setOwnerUserId(agent.getOwnerUserId());
        }
        return vo;
    }

    private String normalizeType(String entityType) {
        String type = entityType == null ? "" : entityType.trim().toUpperCase();
        if (!ENTITY_TYPES.contains(type)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "unsupported entity type");
        }
        return type;
    }

    private Long currentUser() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
