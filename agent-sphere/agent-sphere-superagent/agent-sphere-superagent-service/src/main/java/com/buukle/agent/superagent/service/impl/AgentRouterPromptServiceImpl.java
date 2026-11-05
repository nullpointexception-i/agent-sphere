package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.superagent.service.AgentRouterPromptService;
import com.buukle.agent.superagent.service.support.AgentRouterPrompt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRouterPromptServiceImpl implements AgentRouterPromptService {

    private final SystemConfigSpi systemConfigSpi;

    @Override
    public String resolve() {
        String configured = systemConfigSpi.get(
                SystemConfigKeys.SUPER_AGENT_ROUTER_SYSTEM_PROMPT, null);
        return configured == null || configured.isBlank()
                ? AgentRouterPrompt.DEFAULT : configured;
    }
}
