package com.buukle.agent.superagent.dtvo.enums;

/** Event lifecycle values for {@code agent_event.status}. */
public final class AgentEventStatus {

    public static final String RECEIVED = "RECEIVED";
    public static final String DELIVERED = "DELIVERED";
    public static final String PENDING = "PENDING";
    public static final String PROCESSED = "PROCESSED";
    public static final String FAILED = "FAILED";

    private AgentEventStatus() {
    }
}
