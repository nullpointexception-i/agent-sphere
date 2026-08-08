package com.buukle.agent.tasks.dtvo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Map 字段容错反序列化器：同时接受 JSON 对象与 JSON 字符串两种入参形式。
 * 兼容 as 管理端（发对象）与外部调用方（把整个参数编码为字符串，如 Bole 的 config）。
 */
public class LenientMapDeserializer extends JsonDeserializer<Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Override
    public Map<String, Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == null) {
            p.nextToken();
        }
        if (p.currentToken().isStructStart()) {
            return p.getCodec().readValue(p, MAP_TYPE);
        }
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            throw new IOException("Invalid JSON string for Map field: " + e.getMessage(), e);
        }
    }
}
