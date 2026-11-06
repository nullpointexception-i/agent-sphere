package com.buukle.agent.channel.spi;

/** Publishes ephemeral Agent streaming fragments to a channel-side audience. */
public interface AgentStreamPort {

    /**
     * Delivery must be best-effort and non-blocking of the agent run: failures are
     * logged and dropped, never retried into the caller.
     */
    void publish(AgentStreamEvent event);
}
