package com.buukle.agent.common.constant;

/** 工具安全级别：L1 直接调用，L2 需用户确认。 */
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
