package com.buukle.agent.instance.dtvo.enums;

/**
 * Execution status of the session cleanup task ({@code agent_session_cleanup_run.status}).
 *
 * <p>Deliberately not the generic field convention {@code 'ACTIVE'}: this is the lifecycle of one
 * execution, not a business entity state.
 */
public final class SessionCleanupRunStatusEnum {

    /** Started but not yet in a terminal state. Staying here for a long time (see the {@code stale} check) means the process crashed */
    public static final String STATUS_RUNNING = "RUNNING";
    /** Finished normally (including rehearsals) */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** An exception was thrown during execution */
    public static final String STATUS_FAILED = "FAILED";
    /** Nothing was done: the lock is held by another replica/request, or the kill switch is off */
    public static final String STATUS_SKIPPED = "SKIPPED";

    private SessionCleanupRunStatusEnum() {
    }
}