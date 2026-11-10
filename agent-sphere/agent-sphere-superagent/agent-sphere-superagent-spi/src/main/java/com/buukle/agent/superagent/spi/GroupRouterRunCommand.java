package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Command for a Group Router run executed through the normal runtime. */
public record GroupRouterRunCommand(
        Long sessionId,
        Long threadId,
        Long tenantId,
        String input
) implements Serializable {
}
