package com.buukle.agent.instance.dtvo.enums;

/** 统一 Timeline 行展示类型。 */
public enum TimelineKind {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    CLARIFICATION("clarification"),
    SUBAGENT("subagent"),
    RUN_STATUS("run_status"),
    ERROR("error");

    private final String code;

    TimelineKind(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static TimelineKind from(String code) {
        if (code == null) {
            return null;
        }
        for (TimelineKind k : values()) {
            if (k.code.equals(code)) {
                return k;
            }
        }
        return null;
    }
}