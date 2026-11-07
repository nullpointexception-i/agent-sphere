package com.buukle.agent.runtime.kernel.port.vo;

import com.buukle.agent.common.event.EventType;
import com.buukle.agent.common.event.UserInLoopRecordStatus;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EventType} (sealed interface) deserializer: maps the {@code value()} string to the concrete enum.
 * Used by the Redis event bus ({@code JsonJacksonCodec}) to deserialize {@link RuntimeEventVO} across replicas.
 * Builds the value→enum map statically, covering all permitted types without magic values.
 */
public class EventTypeDeserializer extends JsonDeserializer<EventType> {

    private static final Map<String, EventType> BY_VALUE = new HashMap<>();

    static {
        List<Class<? extends Enum<?>>> types = List.of(
                RunStatus.class,
                ToolCallStatus.class,
                CompactionStatus.class,
                UserInLoopRecordStatus.class,
                FlowEventType.class,
                SessionEventType.class,
                ChromeCommandEventType.class,
                ClarificationStatus.class,
                ToolApprovalEvent.class);
        for (Class<? extends Enum<?>> type : types) {
            for (Object constant : type.getEnumConstants()) {
                EventType eventType = (EventType) constant;
                BY_VALUE.putIfAbsent(eventType.value(), eventType);
            }
        }
    }

    @Override
    public EventType deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getText();
        EventType eventType = BY_VALUE.get(value);
        if (eventType == null) {
            throw new JsonMappingException(parser, "Unknown EventType value: " + value);
        }
        return eventType;
    }
}
