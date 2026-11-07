package com.buukle.agent.tasks.dtvo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Lenient deserializer for Map fields: accepts both a JSON object and a JSON string as input.
 * Compatible with the AS admin UI (sends an object) and external callers (encode the whole parameter
 * as a string, e.g. Bole's config).
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
