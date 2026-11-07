package com.buukle.agent.runtime.kernel.port.vo;


import com.buukle.agent.common.event.EventType;public enum ToolCallStatus implements EventType {
    PENDING, RUNNING, SUCCEEDED, FAILED;

    @Override
    public String value() {
        return name();
    }
}
