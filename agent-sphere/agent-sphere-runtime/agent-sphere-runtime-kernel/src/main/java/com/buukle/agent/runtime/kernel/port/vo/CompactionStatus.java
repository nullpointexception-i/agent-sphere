package com.buukle.agent.runtime.kernel.port.vo;


import com.buukle.agent.common.event.EventType;public enum CompactionStatus implements EventType {
    PENDING, RUNNING, COMPLETED, FAILED;

    @Override
    public String value() {
        return name();
    }
}
