package com.buukle.agent.superagent.spi;

import com.buukle.agent.superagent.dtvo.enums.AgentRouterRunStatus;

import java.io.Serializable;

/** Result of starting an Agent Router run. */
public record AgentRouterRunResult(boolean accepted, Long runId, String status, String message)
        implements Serializable {

    public static AgentRouterRunResult accepted(Long runId, String status) {
        return new AgentRouterRunResult(true, runId, status, null);
    }

    public static AgentRouterRunResult rejected(String message) {
        return new AgentRouterRunResult(false, null, AgentRouterRunStatus.REJECTED, message);
    }
}
