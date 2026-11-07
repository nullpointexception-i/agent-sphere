package com.buukle.agent.capability.skill.dtvo.constants;

public final class SkillConstants {

    /**
     * Maximum character count for a skill definition. The column is JSONB with no length limit;
     * this bound only exists to reject absurdly large payloads.
     */
    public static final int MAX_DEFINITION_LENGTH = 100000;

    private SkillConstants() {
    }
}
