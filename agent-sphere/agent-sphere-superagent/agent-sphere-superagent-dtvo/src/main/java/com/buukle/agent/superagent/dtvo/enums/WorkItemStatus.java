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

    /** Terminal states; both COMPLETED and the legacy DONE are accepted. */
    public static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || DONE.equals(status)
                || FAILED.equals(status) || CANCELLED.equals(status);
    }

    /** Active states that still own a Worker slot. */
    public static boolean isInFlight(String status) {
        return DISPATCHING.equals(status) || RUNNING.equals(status)
                || WAITING.equals(status) || SPECULATING.equals(status);
    }

    /** Normalizes the legacy COMPLETED value to DONE for the task board. */
    public static String normalizeTerminal(String status) {
        return COMPLETED.equals(status) ? DONE : status;
    }

    private WorkItemStatus() {
    }
}
