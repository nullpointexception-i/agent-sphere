package com.buukle.agent.superagent.dtvo.enums;

/** Delivery lifecycle values for {@code agent_event_delivery.status}. */
public final class AgentEventDeliveryStatus {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String RUNNING = "RUNNING";
    public static final String PROCESSED = "PROCESSED";
    public static final String FAILED = "FAILED";

    private AgentEventDeliveryStatus() {
    }
}
