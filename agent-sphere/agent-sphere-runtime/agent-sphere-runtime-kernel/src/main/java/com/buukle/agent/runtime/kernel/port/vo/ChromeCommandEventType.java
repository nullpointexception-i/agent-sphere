package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChromeCommandEventType implements EventType {
    SEND_COMMAND("browser_operation");

    private final String value;

    ChromeCommandEventType(String value) { this.value = value; }

    @Override
    @JsonValue
    public String value() { return value; }
}
