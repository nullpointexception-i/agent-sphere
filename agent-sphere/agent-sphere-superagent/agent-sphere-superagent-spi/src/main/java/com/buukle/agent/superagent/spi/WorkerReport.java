package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Worker Run 终态回报。 */
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
