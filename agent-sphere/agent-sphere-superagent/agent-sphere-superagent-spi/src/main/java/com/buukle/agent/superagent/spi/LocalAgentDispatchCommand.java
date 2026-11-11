package com.buukle.agent.superagent.spi;

import java.io.Serializable;
import java.util.List;

/** Ask a local desktop runtime to execute one batch of router deliveries. */
public record LocalAgentDispatchCommand(
        Long agentId,
        Long threadId,
        List<Long> deliveryIds
) implements Serializable {
}
