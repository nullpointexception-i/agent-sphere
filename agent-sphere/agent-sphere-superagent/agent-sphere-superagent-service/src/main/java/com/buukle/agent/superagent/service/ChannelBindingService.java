package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.domain.AgentChannelBinding;

import java.util.List;

public interface ChannelBindingService {

    AgentChannelBinding create(AgentChannelBinding binding);

    List<AgentChannelBinding> list(Long superAgentId);

    AgentChannelBinding get(Long id);

    AgentChannelBinding updateStatus(Long id, String status);
}
