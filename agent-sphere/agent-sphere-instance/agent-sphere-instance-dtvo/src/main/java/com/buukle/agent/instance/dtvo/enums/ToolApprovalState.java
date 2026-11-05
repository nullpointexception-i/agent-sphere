package com.buukle.agent.instance.dtvo.enums;

/** Persisted status values for tool approval requests and grants. */
public final class ToolApprovalState {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DENIED = "DENIED";
    public static final String CONSUMED = "CONSUMED";
    public static final String DISMISSED = "DISMISSED";

    private ToolApprovalState() {
    }
}
