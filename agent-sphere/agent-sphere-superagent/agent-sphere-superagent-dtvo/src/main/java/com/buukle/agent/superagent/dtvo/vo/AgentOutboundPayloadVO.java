package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Serialized outbox payload consumed by {@code OutboundDispatchScheduler}. */
@Data
public class AgentOutboundPayloadVO implements Serializable {
    private String text;
    private String format;
    private Long replyToId;
    private Long targetMessageId;
}
