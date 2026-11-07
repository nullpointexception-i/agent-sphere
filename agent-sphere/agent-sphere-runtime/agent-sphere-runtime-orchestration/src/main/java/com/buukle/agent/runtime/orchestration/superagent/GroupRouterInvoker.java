package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.superagent.spi.GroupRouterPort;
import com.buukle.agent.superagent.spi.GroupRouterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * LLM adapter for Group Router.
 *
 * <p>superagent-service depends only on {@link GroupRouterPort}; this class, inside orchestration, uses the existing
 * KernelLlmService + route/key resolution, so the Super-Agent domain does not depend back on the runtime.
 */
@Service
@RequiredArgsConstructor
public class GroupRouterInvoker implements GroupRouterPort {

    private final SuperAgentLlmInvoker invoker;

    @Override
    public String classify(GroupRouterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("group router request is required");
        }
        return invoker.invoke(request.instanceId(), request.systemPrompt(),
                request.userPrompt(), request.maxTokens(),
                request.sessionId(), SessionType.GROUP_ROUTER, null, request.threadId(),
                com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType.INTENT_CLASSIFICATION);
    }
}
