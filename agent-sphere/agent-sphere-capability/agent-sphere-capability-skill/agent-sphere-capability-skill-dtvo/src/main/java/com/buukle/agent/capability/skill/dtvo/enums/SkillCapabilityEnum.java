package com.buukle.agent.capability.skill.dtvo.enums;

public final class SkillCapabilityEnum {
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private SkillCapabilityEnum() {
    }

    /** Validates the status value; throws IllegalArgumentException when invalid. */
    public static void assertValidStatus(String status) {
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("Invalid skill status: " + status);
        }
    }
}
