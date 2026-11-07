package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Command to start a Worker Run. */
public record WorkerDispatchCommand(
        Long workItemId,
        Long superAgentId,
        Long threadId,
        Long workerInstanceId,
        String goal,
        String runMode,
        int priority,
        Long ownerUserId,
        Long tenantId,
        String ownerUsername
) implements Serializable {
}
