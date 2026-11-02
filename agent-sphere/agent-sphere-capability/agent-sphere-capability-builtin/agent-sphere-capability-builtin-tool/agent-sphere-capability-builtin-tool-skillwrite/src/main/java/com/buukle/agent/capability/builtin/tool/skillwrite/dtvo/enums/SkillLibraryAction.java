package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.enums;

/** 技能库工具（builtin_8）支持的 action。 */
public enum SkillLibraryAction {
    LIST("list"),
    COUNT("count"),
    SEARCH("search"),
    SEARCH_BY_TITLE("search_by_title"),
    GET("get");

    private final String value;

    SkillLibraryAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SkillLibraryAction from(String value) {
        if (value == null) return null;
        for (SkillLibraryAction action : values()) {
            if (action.value.equalsIgnoreCase(value)) return action;
        }
        return null;
    }
}
