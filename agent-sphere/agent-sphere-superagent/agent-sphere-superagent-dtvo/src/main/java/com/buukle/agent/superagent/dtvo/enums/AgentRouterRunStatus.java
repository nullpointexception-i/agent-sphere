package com.buukle.agent.superagent.dtvo.enums;

/** Agent Router run result values reported back to deliveries. */
public final class AgentRouterRunStatus {

    public static final String QUEUED = "QUEUED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private AgentRouterRunStatus() {
    }
}
