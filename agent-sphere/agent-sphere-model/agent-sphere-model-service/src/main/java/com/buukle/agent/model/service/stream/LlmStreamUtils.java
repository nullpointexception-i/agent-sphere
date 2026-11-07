package com.buukle.agent.model.service.stream;

import com.fasterxml.jackson.databind.JsonNode;

/** Small shared utility for parsing LLM streaming response fields. */
public final class LlmStreamUtils {

    private LlmStreamUtils() {
    }

    /**
     * Return the first "non-empty text" field value in order.
     *
     * <p>Reasoning field names differ across gateways (vLLM/Qwen3 use {@code reasoning}, DeepSeek/Zhipu use
     * {@code reasoning_content}), and empty strings may be present at the same time; an empty string must not
     * shadow subsequent fields.
     *
     * @return the first non-empty text; null when none is present
     */
    public static String firstTextual(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (field == null) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isEmpty()) {
                return value.asText();
            }
        }
        return null;
    }
}
