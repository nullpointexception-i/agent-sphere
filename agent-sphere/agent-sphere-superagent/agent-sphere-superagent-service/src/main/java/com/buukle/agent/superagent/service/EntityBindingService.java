package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.BindEntityAgentDTO;
import com.buukle.agent.superagent.dtvo.EntityAgentBindingVO;

import java.util.List;

public interface EntityBindingService {
    List<EntityAgentBindingVO> list(String entityType, Long entityId);

    EntityAgentBindingVO bind(BindEntityAgentDTO dto);

    void unbind(String entityType, Long entityId, Long agentId);
}
