package com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.enums;

/** Actions supported by the skill library tool (builtin_8). */
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
