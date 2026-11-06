package com.buukle.agent.channel.spi;

/** Fragment phases carried by {@link AgentStreamEvent}. */
public final class AgentStreamPhase {

    /** Model reasoning (chain-of-thought) fragments. */
    public static final String THINKING = "thinking";

    /** Assistant reply fragments; may be reset when a tool call starts. */
    public static final String REPLY = "reply";

    /** Run / tool lifecycle status updates; no delta payload. */
    public static final String STATUS = "status";

    private AgentStreamPhase() {
    }
}
