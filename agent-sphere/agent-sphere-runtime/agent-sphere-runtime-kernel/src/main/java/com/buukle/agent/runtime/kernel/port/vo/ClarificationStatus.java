package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ClarificationStatus implements EventType {
    PENDING("clarification_pending"),
    RESPONDED("clarification_responded"),
    DISMISSED("clarification_dismissed");

    private final String value;

    ClarificationStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
