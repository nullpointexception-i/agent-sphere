package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** 启动 Worker Run 的命令。 */
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
