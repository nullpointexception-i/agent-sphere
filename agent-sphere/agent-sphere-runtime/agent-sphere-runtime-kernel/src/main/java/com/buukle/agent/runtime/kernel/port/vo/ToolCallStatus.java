package com.buukle.agent.runtime.kernel.port.vo;

public enum ToolCallStatus implements EventType {
    PENDING, RUNNING, SUCCEEDED, FAILED;

    @Override
    public String value() { return name(); }
}
