package com.buukle.agent.runtime.kernel.port.vo;

public enum SessionStatus implements EventType {
    TITLE_UPDATED;

    @Override
    public String value() {
        return name();
    }
}
