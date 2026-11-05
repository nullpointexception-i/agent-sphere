package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Agent Router 决策请求。 */
public record AgentRouterRequest(
        Long instanceId,
        Long sessionId,
        Long threadId,
        Long superAgentId,
        String systemPrompt,
        String userPrompt,
        Integer maxTokens
) implements Serializable {
}
