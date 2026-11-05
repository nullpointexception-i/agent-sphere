package com.buukle.agent.superagent.dtvo.enums;

/** Outbox lifecycle values for {@code agent_outbound_message.status}. */
public final class AgentOutboundMessageStatus {

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String RETRY = "RETRY";
    public static final String FAILED = "FAILED";

    private AgentOutboundMessageStatus() {
    }
}
