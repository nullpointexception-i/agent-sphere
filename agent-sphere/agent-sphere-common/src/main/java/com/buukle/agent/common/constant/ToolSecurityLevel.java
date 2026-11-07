package com.buukle.agent.common.constant;

/** Tool security level: L1 is called directly, L2 requires user confirmation. */
public enum ToolSecurityLevel {
    L1,
    L2;

    public static ToolSecurityLevel from(String value) {
        if (value == null || value.isBlank()) {
            return L1;
        }
        for (ToolSecurityLevel level : values()) {
            if (level.name().equalsIgnoreCase(value)) {
                return level;
            }
        }
        return L1;
    }

    public boolean requiresApproval() {
        return this == L2;
    }
}
