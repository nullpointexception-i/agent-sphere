package com.buukle.agent.runtime.kernel.port.vo;


import com.buukle.agent.common.event.EventType;/** L2 tool approval lifecycle events (not timeline clarification rows). */
public enum ToolApprovalStatus implements EventType {
    TOOL_APPROVAL_PENDING,
    TOOL_APPROVAL_RESPONDED,
    TOOL_APPROVAL_DISMISSED;

    @Override
    public String value() {
        return name();
    }
}
