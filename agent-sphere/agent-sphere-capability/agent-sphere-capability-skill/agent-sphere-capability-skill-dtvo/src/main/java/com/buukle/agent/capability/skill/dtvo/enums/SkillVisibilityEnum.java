package com.buukle.agent.capability.skill.dtvo.enums;

/** skill hub 可见性：PRIVATE=仅作者可见（默认），PUBLIC=hub 公开（直接公开，无审核）。 */
public final class SkillVisibilityEnum {
    public static final String PRIVATE = "PRIVATE";
    public static final String PUBLIC = "PUBLIC";

    private SkillVisibilityEnum() {
    }

    /** 校验可见性值，非法抛 IllegalArgumentException。 */
    public static void assertValidVisibility(String visibility) {
        if (!PRIVATE.equals(visibility) && !PUBLIC.equals(visibility)) {
            throw new IllegalArgumentException("非法 skill 可见性: " + visibility);
        }
    }
}
