package com.buukle.agent.runtime.kernel.port.vo;


import com.buukle.agent.common.event.EventType;public enum SessionStatus implements EventType {
    TITLE_UPDATED;

    @Override
    public String value() {
        return name();
    }
}
