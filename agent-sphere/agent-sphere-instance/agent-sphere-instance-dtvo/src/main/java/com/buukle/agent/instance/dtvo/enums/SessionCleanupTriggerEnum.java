package com.buukle.agent.instance.dtvo.enums;

/**
 * Trigger type of the session cleanup task ({@code agent_session_cleanup_run.trigger_type}).
 *
 * <p>Both must leave a trace: the scheduled task is a routine operational action, while a manual
 * trigger usually corresponds to "a disk alert needs immediate handling", so afterwards it must be
 * possible to tell who pressed it and when.
 */
public final class SessionCleanupTriggerEnum {

    /** Triggered by cron */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    /** Manually triggered through an admin endpoint */
    public static final String TRIGGER_MANUAL = "MANUAL";

    private SessionCleanupTriggerEnum() {
    }
}