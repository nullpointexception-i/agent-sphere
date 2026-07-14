package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonValue;

public sealed interface EventType
        permits RunStatus, ToolCallStatus, CompactionStatus,
        UserInLoopRecordStatus, FlowEventType, SessionStatus,
        ScreenshotEventType, ChromeCommandEventType, ClarificationStatus {
    @JsonValue
    String value();
}
