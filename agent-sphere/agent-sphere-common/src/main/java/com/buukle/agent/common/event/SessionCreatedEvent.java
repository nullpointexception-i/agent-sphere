package com.buukle.agent.common.event;

import lombok.Data;

@Data
public class SessionCreatedEvent {
    private Long sessionId;
    private Long agentInstanceId;
}
