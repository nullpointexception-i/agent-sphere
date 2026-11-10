package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Result of a Group Router run: the run id and its final assistant text. */
public record GroupRouterRunResult(
        boolean accepted,
        Long runId,
        String reply,
        String message
) implements Serializable {

    public static GroupRouterRunResult accepted(Long runId, String reply) {
        return new GroupRouterRunResult(true, runId, reply, null);
    }

    public static GroupRouterRunResult rejected(String message) {
        return new GroupRouterRunResult(false, null, null, message);
    }
}
