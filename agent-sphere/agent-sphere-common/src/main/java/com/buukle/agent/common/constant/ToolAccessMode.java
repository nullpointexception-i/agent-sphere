package com.buukle.agent.common.constant;

/** Read/write access of a tool against its target system. */
public enum ToolAccessMode {
    READ,
    WRITE,
    READ_WRITE;

    public static ToolAccessMode from(String value) {
        if (value == null || value.isBlank()) {
            return READ_WRITE;
        }
        for (ToolAccessMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return READ_WRITE;
    }
}
