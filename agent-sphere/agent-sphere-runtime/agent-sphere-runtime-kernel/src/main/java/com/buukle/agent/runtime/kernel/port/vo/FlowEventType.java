package com.buukle.agent.runtime.kernel.port.vo;


import com.buukle.agent.common.event.EventType;

public enum FlowEventType implements EventType {
    REASONING_TOKEN("reasoning_token"),
    CONTENT_TOKEN("content_token");

    private final String value;

    FlowEventType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
