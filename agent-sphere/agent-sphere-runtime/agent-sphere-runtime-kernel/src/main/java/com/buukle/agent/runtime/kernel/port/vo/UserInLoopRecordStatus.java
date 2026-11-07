package com.buukle.agent.runtime.kernel.port.vo;


import com.buukle.agent.common.event.EventType;public enum UserInLoopRecordStatus implements EventType {
    WAITING, RESPONDED;

    @Override
    public String value() {
        return name();
    }
}
