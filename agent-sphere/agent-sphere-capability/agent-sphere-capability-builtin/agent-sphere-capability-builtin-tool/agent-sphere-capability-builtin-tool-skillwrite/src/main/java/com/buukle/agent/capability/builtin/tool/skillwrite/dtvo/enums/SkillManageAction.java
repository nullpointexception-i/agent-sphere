package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.enums;

/** 技能管理工具（builtin_9）支持的 action。 */
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
