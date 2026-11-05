package com.buukle.agent.channel.spi;

import java.io.Serializable;

/** 出站消息统一形状。targetMessageId 非空表示“更新已有消息”，由信道适配器决定是否支持。 */
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
