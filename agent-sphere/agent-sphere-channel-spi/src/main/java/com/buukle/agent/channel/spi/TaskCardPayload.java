package com.buukle.agent.channel.spi;

import lombok.Data;

import java.io.Serializable;

/** Typed card body embedded in an outbound CARD message. */
@Data
public class TaskCardPayload implements Serializable {
    private String cardType;
    private Long workItemId;
    private String title;
    private String status;
    private String summary;
    private Long superAgentId;
}
