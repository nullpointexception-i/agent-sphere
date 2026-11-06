package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Immutable runtime event envelope. See {@link RuntimeEventDataVO} for why the payload
 * is a record: listeners share the same instance, so mutation must be impossible.
 *
 * <p>{@code @JsonAutoDetect} keeps serialization field-based so the compatibility
 * getters do not introduce duplicate JSON keys.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record RuntimeEventVO(EventType eventType, RuntimeEventDataVO data) {

    public EventType getEventType() {
        return eventType;
    }

    public RuntimeEventDataVO getData() {
        return data;
    }
}
