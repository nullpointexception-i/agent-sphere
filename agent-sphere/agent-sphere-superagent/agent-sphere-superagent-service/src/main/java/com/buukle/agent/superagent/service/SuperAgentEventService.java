package com.buukle.agent.superagent.service;

/** Event operations exposed to the Super-Agent console. */
public interface SuperAgentEventService {

    /**
     * Re-queue the delivery of one event for one Super-Agent.
     *
     * <p>Events that are still pending, claimed or running are rejected to avoid
     * duplicate Router runs. Terminal deliveries are reset to {@code PENDING}
     * so the Agent Router consumer can pick them up again.
     */
    void retry(Long superAgentId, Long eventId);
}
