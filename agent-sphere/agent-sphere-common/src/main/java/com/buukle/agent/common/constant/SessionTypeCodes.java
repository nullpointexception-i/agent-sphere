package com.buukle.agent.common.constant;

/**
 * Session type values.
 *
 * <p>Frozen DB/API values. Kept in common so access-control code can reason about internal
 * sessions without depending on the instance DTO module.
 */
public final class SessionTypeCodes {

    public static final String CONSOLE = "CONSOLE";
    public static final String WIDGET = "WIDGET";
    public static final String TASK = "TASK";
    public static final String GROUP_ROUTER = "GROUP_ROUTER";
    public static final String ROUTER = "ROUTER";
    public static final String WORKER = "WORKER";
    public static final String TRAINING = "TRAINING";
    public static final String WATCHDOG = "WATCHDOG";
    /** Tenant-level project assistant; one session per user per project. */
    public static final String PROJECT_ROUTER = "PROJECT_ROUTER";

    private SessionTypeCodes() {
    }
}
