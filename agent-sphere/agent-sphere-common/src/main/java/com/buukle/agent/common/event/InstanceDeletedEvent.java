package com.buukle.agent.common.event;

import lombok.Data;

/** Published after an instance row is deleted, so owning aggregates can clean up references. */
@Data
public class InstanceDeletedEvent {
    private Long instanceId;

    public InstanceDeletedEvent() {
    }

    public InstanceDeletedEvent(Long instanceId) {
        this.instanceId = instanceId;
    }
}
