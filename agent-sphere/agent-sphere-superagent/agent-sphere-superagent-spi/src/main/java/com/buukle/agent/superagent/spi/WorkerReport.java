package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Terminal-state report of a Worker Run. */
public record WorkerReport(
        Long workItemId,
        Long superAgentId,
        Long workerSessionId,
        Long runId,
        String status,
        String summary,
        String errorMessage
) implements Serializable {
}
