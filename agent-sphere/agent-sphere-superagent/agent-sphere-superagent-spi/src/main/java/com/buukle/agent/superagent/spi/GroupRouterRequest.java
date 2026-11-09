package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Group message dispatch request. */
public record GroupRouterRequest(
        Long instanceId,
        Long modelRouteId,
        Long sessionId,
        Long threadId,
        String systemPrompt,
        String userPrompt,
        Integer maxTokens
) implements Serializable {
}
