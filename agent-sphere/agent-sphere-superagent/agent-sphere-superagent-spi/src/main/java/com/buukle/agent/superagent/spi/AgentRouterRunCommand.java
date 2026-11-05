package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Command for an Agent Router run. */
public record AgentRouterRunCommand(
        Long sessionId,
        Long superAgentId,
        Long threadId,
        String input,
        String runMode
) implements Serializable {
}
