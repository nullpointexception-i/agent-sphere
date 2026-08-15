package com.buukle.agent.runtime.kernel.port.vo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EventType}（sealed interface）反序列化器：按 {@code value()} 字符串映射到具体枚举。
 * 供 Redis 事件总线（{@code JsonJacksonCodec}）跨副本反序列化 {@link RuntimeEventVO} 使用。
 * 静态构建 value→枚举 映射，覆盖全部 permitted 类型，无需维护魔法值。
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
                SessionStatus.class,
                ChromeCommandEventType.class,
                ClarificationStatus.class);
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
