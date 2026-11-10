package com.buukle.agent.superagent.repository;

import lombok.Data;

/** One pending delivery group: an avatar inside one thread. */
@Data
public class PendingDeliveryGroup {
    private Long agentId;
    private Long threadId;
    private Long pending;
    private Long firstId;
}
