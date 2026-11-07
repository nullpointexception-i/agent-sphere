package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.enums;

/** Actions supported by the skill management tool (builtin_9). */
public enum SkillManageAction {
    CREATE("create"),
    APPEND("append"),
    UPDATE("update");

    private final String value;

    SkillManageAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SkillManageAction from(String value) {
        if (value == null) return null;
        for (SkillManageAction action : values()) {
            if (action.value.equalsIgnoreCase(value)) return action;
        }
        return null;
    }
}
