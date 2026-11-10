package com.buukle.agent.superagent.dtvo.enums;

/** Local task lifecycle status. */
public final class LocalTaskStatus {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    private LocalTaskStatus() {
    }
}
