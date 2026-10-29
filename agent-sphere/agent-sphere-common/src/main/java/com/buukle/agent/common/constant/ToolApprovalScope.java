package com.buukle.agent.common.constant;

/** L2 工具的审批范围。 */
public enum ToolApprovalScope {
    SESSION,
    RUN,
    ONCE;

    public static ToolApprovalScope from(String value) {
        if (value == null || value.isBlank()) {
            return ONCE;
        }
        for (ToolApprovalScope scope : values()) {
            if (scope.name().equalsIgnoreCase(value)) {
                return scope;
            }
        }
        return ONCE;
    }
}
