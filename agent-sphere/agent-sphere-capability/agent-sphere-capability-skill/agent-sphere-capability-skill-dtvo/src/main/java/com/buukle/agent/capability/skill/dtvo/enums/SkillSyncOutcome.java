package com.buukle.agent.capability.skill.dtvo.enums;

/**
 * Outcome of a single copy in one auto-sync round.
 *
 * <p>Why this exists: when auto-sync "has no effect", the question that matters is "why did this
 * skill not keep up?", yet the old {@code syncFromOrigin} returned only a boolean for every case
 * (true=synced, false=skipped), so source deleted, unpublished, version not ahead and conditional
 * update failed - four completely different causes - all looked identical in the logs.
 *
 * <p>Every non-UPDATED value is written verbatim into {@code capability_skill_sync_run.detail},
 * so the admin console can see which copy was not synced and why.
 */
public enum SkillSyncOutcome {

    /** Origin content was synced into the copy */
    UPDATED,

    /** The origin skill was deleted (logical delete); the copy stays on old content forever */
    SOURCE_GONE,

    /** The origin skill is no longer public; the copy no longer follows it */
    NOT_PUBLIC,

    /** The origin version is not ahead of the version already synced, nothing to do (the normal case for most copies) */
    VERSION_NOT_AHEAD,

    /** Conditional UPDATE affected 0 rows: origin_version changed concurrently, counted as not synced this round */
    CONCURRENT_UPDATE
}
