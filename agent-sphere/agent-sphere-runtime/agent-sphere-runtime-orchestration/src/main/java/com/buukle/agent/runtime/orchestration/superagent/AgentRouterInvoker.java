package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.superagent.spi.AgentRouterPort;
import com.buukle.agent.superagent.spi.AgentRouterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Agent Router 的 LLM 适配器，复用 {@link SuperAgentLlmInvoker}。 */
@Service
@RequiredArgsConstructor
public class AgentRouterInvoker implements AgentRouterPort {

    private final SuperAgentLlmInvoker invoker;

    @Override
    public String decide(AgentRouterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("agent router request is required");
        }
        return invoker.invoke(request.instanceId(), request.systemPrompt(),
                request.userPrompt(), request.maxTokens(),
                request.sessionId(), "ROUTER", request.superAgentId(), request.threadId(),
                com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType.INTENT_CLASSIFICATION);
    }
}
