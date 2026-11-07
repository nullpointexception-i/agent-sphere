package com.buukle.agent.runtime.kernel.runner.support;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

/** Safe field reader for tool-call argument JSON. */
public final class ToolJsonSupport {

    private ToolJsonSupport() {
    }

    /** Reads a string field; missing/invalid always returns null (finalization must tolerate dirty input). */
    public static String field(String argumentsJson, String field) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(argumentsJson).path(field);
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
