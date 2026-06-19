package com.buukle.agent.runtime.kernel.port.vo;

public enum RunStatus implements EventType {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED;

    @Override
    public String value() { return name(); }
}
