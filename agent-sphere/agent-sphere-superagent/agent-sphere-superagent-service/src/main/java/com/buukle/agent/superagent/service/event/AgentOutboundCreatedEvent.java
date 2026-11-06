package com.buukle.agent.superagent.service.event;

/** Fired after an outbound message has been committed; wakes the Outbound dispatcher. */
public record AgentOutboundCreatedEvent(Long outboundId) {
}
