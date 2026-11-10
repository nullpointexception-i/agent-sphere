package com.buukle.agent.superagent.service.event;

import java.io.Serializable;
import java.util.List;

/**
 * Realtime dispatch hint published after delivery rows commit.
 *
 * <p>{@code deliveryIds} may be empty when the producer only knows the (agent, thread) pair,
 * for example after a clarification answer; the dispatcher then resolves the pending rows.
 */
public record AgentDeliveryReadyEvent(
        Long agentId,
        Long threadId,
        List<Long> deliveryIds
) implements Serializable {
}
