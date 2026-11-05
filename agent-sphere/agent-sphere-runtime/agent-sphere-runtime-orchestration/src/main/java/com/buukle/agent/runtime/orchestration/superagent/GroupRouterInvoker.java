package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.superagent.spi.GroupRouterPort;
import com.buukle.agent.superagent.spi.GroupRouterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Group Router 的 LLM 适配器。
 *
 * <p>superagent-service 只依赖 {@link GroupRouterPort}；本类在 orchestration 内用现有
 * KernelLlmService + 路由/密钥解析实现，避免 Super-Agent 域反向依赖运行时。
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
