package com.buukle.agent.superagent.service.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes {@link AgentOutboundCreatedEvent} after an outbound row is inserted. */
@Component
@RequiredArgsConstructor
public class AgentOutboundWakePublisher {

    private final ApplicationEventPublisher publisher;

    /** Idempotent: only call after the outbound row insert succeeded. */
    public void publish(Long outboundId) {
        if (outboundId == null) {
            return;
        }
        publisher.publishEvent(new AgentOutboundCreatedEvent(outboundId));
    }
}
