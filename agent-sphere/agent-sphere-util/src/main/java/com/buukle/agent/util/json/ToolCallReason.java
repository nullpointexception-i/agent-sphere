package com.buukle.agent.util.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads and strips the tool-call reason (the reserved argument {@link #REASON_KEY}).
 *
 * <p>The model carries an extra {@code _reason} in tool_call.arguments, which the frontend uses to
 * show "why this tool was called". That field is not an input of any business tool, so it must be
 * stripped before execution; it is also kept in the original arguments_json for auditing.
 */
public final class ToolCallReason {

    /** Reserved argument name in tool schemas, so it cannot collide with an MCP/business tool's own reason parameter. */
    public static final String REASON_KEY = "_reason";
    /** Maximum reason length (characters). */
    public static final int MAX_REASON_CHARS = 200;
    /** Description injected into every tool schema. */
    public static final String SCHEMA_DESCRIPTION =
            "Short user-visible reason for calling this tool now. "
                    + "Do not include hidden chain-of-thought or sensitive data.";

    private ToolCallReason() {
    }

    /** Extracts and normalizes the reason; returns null when it is missing or the JSON is invalid. */
    public static String extract(String argsJson) {
        JsonNode node = parse(argsJson);
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode reason = node.get(REASON_KEY);
        if (reason == null || !reason.isTextual()) {
            return null;
        }
        return normalize(reason.asText());
    }

    /** Removes the reserved argument; returns the input unchanged when the field is absent or the JSON is invalid. */
    public static String strip(String argsJson) {
        JsonNode node = parse(argsJson);
        if (node == null || !node.isObject() || !node.has(REASON_KEY)) {
            return argsJson;
        }
        ObjectNode copy = node.deepCopy();
        copy.remove(REASON_KEY);
        try {
            return JsonUtils.getMapper().writeValueAsString(copy);
        } catch (Exception e) {
            return argsJson;
        }
    }

    /** Collapses to a single line, trims and truncates; returns null when blank. */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > MAX_REASON_CHARS
                ? normalized.substring(0, MAX_REASON_CHARS)
                : normalized;
    }

    private static JsonNode parse(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(argsJson);
        } catch (Exception e) {
            return null;
        }
    }
}
