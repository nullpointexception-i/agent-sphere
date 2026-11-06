package com.buukle.agent.superagent.spi;

/** Router parked-input kinds bridged to the IM channel. */
public final class SessionInputType {

    /** Clarification pending (`agent_pending_clarification`). */
    public static final String CLARIFICATION = "CLARIFICATION";

    /** L2 tool approval pending (`agent_tool_approval`). */
    public static final String APPROVAL = "APPROVAL";

    private SessionInputType() {
    }
}
