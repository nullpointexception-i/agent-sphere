package com.buukle.agent.instance.dtvo.enums;

/** 统一 Timeline 行展示状态。 */
public enum TimelineState {
    RUNNING("RUNNING"),
    STARTED("started"),
    IN_PROGRESS("in_progress"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED"),
    PENDING("PENDING"),
    ANSWERED("ANSWERED"),
    TIMEOUT("TIMEOUT");

    private final String code;

    TimelineState(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static TimelineState from(String code) {
        if (code == null) {
            return null;
        }
        for (TimelineState s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }
}