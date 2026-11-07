package com.buukle.agent.common.constant;

/** Execution mode of a single run; write mode by default. */
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
