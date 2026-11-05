package com.buukle.agent.superagent.spi;

/** Reports Agent Router run terminal state back to delivery state. */
public interface AgentRouterReportPort {
    void onRouterTerminal(Long sessionId, Long runId, String status);
}
