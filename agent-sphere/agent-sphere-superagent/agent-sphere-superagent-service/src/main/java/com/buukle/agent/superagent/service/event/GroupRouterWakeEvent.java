package com.buukle.agent.superagent.service.event;

/** Fired after a pending group event has been committed; wakes the Group Router immediately. */
public record GroupRouterWakeEvent(Long threadId) {
}
