package com.buukle.agent.channel.spi;

/**
 * Channel adapter contract.
 *
 * <p>Protocol conversion only: signature verification, parsing, thread identification and sending;
 * no business decisions.
 */
public interface ChannelAdapter {

    /** IM_BUILTIN / WEBHOOK / CODE_HOST / MAIL. */
    String type();

    /**
     * Parses an inbound request. Implementations own signature verification and field validation.
     *
     * @return normalized events; an empty list when there is no valid event
     */
    java.util.List<NormalizedEvent> parse(InboundRequest request);

    /** Sends an outbound message. */
    SendResult send(OutboundMessage message);

    /** Updates an already sent message (e.g. a task card status change); unsupported by default. */
    default SendResult update(OutboundMessage message) {
        return SendResult.failed("update not supported by " + type());
    }
}
