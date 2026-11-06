package com.buukle.agent.superagent.service.event;

/** Fired after agent event deliveries have been committed; wakes the Agent Router. */
public record AgentDeliveryCreatedEvent(Long threadId) {
}
