package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ScreenshotEventType implements EventType {
    PAGE_SCREENSHOT("page_screenshot");

    private final String value;

    ScreenshotEventType(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
