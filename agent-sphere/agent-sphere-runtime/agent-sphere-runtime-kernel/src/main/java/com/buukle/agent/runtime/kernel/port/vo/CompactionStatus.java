package com.buukle.agent.runtime.kernel.port.vo;

public enum CompactionStatus implements EventType {
    PENDING, RUNNING, COMPLETED, FAILED;

    @Override
    public String value() { return name(); }
}
