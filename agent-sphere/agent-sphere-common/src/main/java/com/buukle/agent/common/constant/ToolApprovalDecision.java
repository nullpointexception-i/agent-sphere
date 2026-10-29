package com.buukle.agent.common.constant;

/** User decision for an L2 tool approval card. */
public enum ToolApprovalDecision {
    DENY,
    ALLOW_ONCE,
    ALLOW_RUN,
    ALLOW_SESSION;

    public static ToolApprovalDecision from(String value) {
        if (value == null || value.isBlank()) {
            return DENY;
        }
        for (ToolApprovalDecision d : values()) {
            if (d.name().equalsIgnoreCase(value)) {
                return d;
            }
        }
        return DENY;
    }

    /** Whether this decision is allowed by the tool's configured maximum scope. */
    public boolean allowedFor(ToolApprovalScope scope) {
        ToolApprovalScope max = scope == null ? ToolApprovalScope.ONCE : scope;
        return switch (this) {
            case DENY, ALLOW_ONCE -> true;
            case ALLOW_RUN -> max == ToolApprovalScope.RUN || max == ToolApprovalScope.SESSION;
            case ALLOW_SESSION -> max == ToolApprovalScope.SESSION;
        };
    }

    public boolean allowed() {
        return this != DENY;
    }
}
