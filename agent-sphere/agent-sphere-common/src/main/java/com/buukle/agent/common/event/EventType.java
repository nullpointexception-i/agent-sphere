package com.buukle.agent.common.event;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Contract implemented by every runtime event enum.
 *
 * <p>This interface lives in {@code common} so that persisted status enums (for example
 * {@code RunStatus}) can be shared by business modules without depending on the runtime kernel.
 * It is deliberately not sealed: event enums are spread across modules and the compiler cannot
 * enumerate them from here.
 */
public interface EventType {

    @JsonValue
    String value();
}
