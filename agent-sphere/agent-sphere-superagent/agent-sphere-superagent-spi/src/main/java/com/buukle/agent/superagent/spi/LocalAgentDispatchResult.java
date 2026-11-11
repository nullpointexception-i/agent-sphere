package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Result of dispatching a local task. */
public record LocalAgentDispatchResult(
        boolean accepted,
        Long taskId,
        String message
) implements Serializable {
}
