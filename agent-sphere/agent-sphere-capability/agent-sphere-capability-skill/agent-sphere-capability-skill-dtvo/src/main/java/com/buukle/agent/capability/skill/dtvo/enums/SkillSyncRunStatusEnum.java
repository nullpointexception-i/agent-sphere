package com.buukle.agent.capability.skill.dtvo.enums;

/**
 * Execution status of automatic skill sync ({@code capability_skill_sync_run.status}).
 *
 * <p>Same shape as {@code SessionCleanupRunStatusEnum}, so the frontend polling panels of both
 * tasks can share the same logic.
 */
public final class SkillSyncRunStatusEnum {

    /** Started but not yet terminal; sitting here for a long time (see stale) means the process crashed */
    public static final String STATUS_RUNNING = "RUNNING";
    /** Finished normally */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** Did nothing: the lock was taken or the global switch was off */
    public static final String STATUS_SKIPPED = "SKIPPED";
    /** An exception was thrown during execution */
    public static final String STATUS_FAILED = "FAILED";

    private SkillSyncRunStatusEnum() {
    }
}
