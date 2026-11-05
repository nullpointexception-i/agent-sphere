package com.buukle.agent.superagent.dtvo.enums;

/** Work item state machine values. */
public final class WorkItemStatus {

    public static final String QUEUED = "QUEUED";
    public static final String DISPATCHING = "DISPATCHING";
    public static final String RUNNING = "RUNNING";
    public static final String WAITING = "WAITING";
    public static final String BLOCKED = "BLOCKED";
    public static final String COMPLETED = "COMPLETED";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SPECULATING = "SPECULATING";

    private WorkItemStatus() {
    }
}
