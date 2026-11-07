package com.buukle.agent.capability.skill.dtvo.enums;

/** Skill hub visibility: PRIVATE = author-only (default), PUBLIC = listed in the hub (published directly, no review). */
public final class SkillVisibilityEnum {
    public static final String PRIVATE = "PRIVATE";
    public static final String PUBLIC = "PUBLIC";

    private SkillVisibilityEnum() {
    }

    /** Validates the visibility value; throws IllegalArgumentException when invalid. */
    public static void assertValidVisibility(String visibility) {
        if (!PRIVATE.equals(visibility) && !PUBLIC.equals(visibility)) {
            throw new IllegalArgumentException("Invalid skill visibility: " + visibility);
        }
    }
}
