package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** 群消息分派请求。 */
public record GroupRouterRequest(
        Long instanceId,
        Long sessionId,
        Long threadId,
        String systemPrompt,
        String userPrompt,
        Integer maxTokens
) implements Serializable {
}
