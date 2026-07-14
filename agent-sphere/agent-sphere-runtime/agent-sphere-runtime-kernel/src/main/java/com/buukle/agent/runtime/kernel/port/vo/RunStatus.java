package com.buukle.agent.runtime.kernel.port.vo;

public enum RunStatus implements EventType {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, AWAITING_USER;

    @Override
    public String value() {
        return name();
    }
}
