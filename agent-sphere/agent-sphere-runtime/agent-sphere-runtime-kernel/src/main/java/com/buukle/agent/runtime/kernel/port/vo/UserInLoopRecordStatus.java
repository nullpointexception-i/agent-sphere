package com.buukle.agent.runtime.kernel.port.vo;

public enum UserInLoopRecordStatus implements EventType {
    WAITING, RESPONDED;

    @Override
    public String value() {
        return name();
    }
}
