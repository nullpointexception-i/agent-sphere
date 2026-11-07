package com.buukle.agent.channel.spi;

import java.io.Serializable;

/**
 * Unified shape of an outbound message. A non-null {@code targetMessageId} means "update an existing
 * message"; the channel adapter decides whether that is supported.
 */
public record OutboundMessage(
        Long superAgentId,
        Long threadId,
        String text,
        String format,
        Long replyToId,
        String idempotencyKey,
        Long targetMessageId
) implements Serializable {

    public OutboundMessage(Long superAgentId, Long threadId, String text, String format,
                           Long replyToId, String idempotencyKey) {
        this(superAgentId, threadId, text, format, replyToId, idempotencyKey, null);
    }
}
