package com.buukle.agent.common.constant;

/** 单次 run 的执行模式；默认写模式。 */
public enum RunMode {
    WRITE,
    READ_ONLY;

    public static RunMode from(String value) {
        if (value == null || value.isBlank()) {
            return WRITE;
        }
        for (RunMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return WRITE;
    }

    public boolean readOnly() {
        return this == READ_ONLY;
    }
}
