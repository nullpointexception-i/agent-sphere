package com.buukle.agent.capability.skill.dtvo.enums;

public final class SkillCapabilityEnum {
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private SkillCapabilityEnum() {
    }

    /** 校验状态值，非法抛 IllegalArgumentException。 */
    public static void assertValidStatus(String status) {
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("非法 skill 状态: " + status);
        }
    }
}
