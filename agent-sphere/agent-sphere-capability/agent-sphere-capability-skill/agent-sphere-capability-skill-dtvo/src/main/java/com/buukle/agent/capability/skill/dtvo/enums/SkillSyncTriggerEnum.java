package com.buukle.agent.capability.skill.dtvo.enums;

/**
 * Trigger types for automatic skill sync ({@code capability_skill_sync_run.trigger_type}).
 */
public final class SkillSyncTriggerEnum {

    /** Scheduled sweep (one round per {@code buukle.agent.skill.auto-update-interval}) */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    /** Manual "check for updates" action from the admin console */
    public static final String TRIGGER_MANUAL = "MANUAL";

    private SkillSyncTriggerEnum() {
    }
}