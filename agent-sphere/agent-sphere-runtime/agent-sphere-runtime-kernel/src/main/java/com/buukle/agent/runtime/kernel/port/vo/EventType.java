package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = EventTypeDeserializer.class)
public sealed interface EventType
        permits RunStatus, ToolCallStatus, CompactionStatus,
        UserInLoopRecordStatus, FlowEventType, SessionStatus,
        ChromeCommandEventType, ClarificationStatus {
    @JsonValue
    String value();
}
