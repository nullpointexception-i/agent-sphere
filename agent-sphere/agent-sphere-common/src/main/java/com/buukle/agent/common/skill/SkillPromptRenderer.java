package com.buukle.agent.common.skill;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Renderer for {@code {{path}}} placeholders (safe: no expression/script/SpEL/Shell execution).
 * Supports nested paths ({{candidate.name}}) and automatic JSON serialization for objects/arrays; a
 * missing field raises a clear error.
 */
public final class SkillPromptRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "{{";
    private static final String SUFFIX = "}}";
    private static final String WHOLE_INPUT = "input";

    private SkillPromptRenderer() {
    }

    /** Renders the template; argsJson is the JSON of the skill call arguments. */
    public static String render(String template, String argsJson) {
        if (template == null || template.isBlank()) {
            return "";
        }
        JsonNode args;
        try {
            args = JSON.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            throw new InvalidSubRunDefinition("skill arguments are not valid JSON: " + e.getMessage());
        }
        if (args == null || !args.isObject()) {
            throw new InvalidSubRunDefinition("skill arguments must be a JSON object");
        }
        StringBuilder out = new StringBuilder();
        int idx = 0;
        while (idx < template.length()) {
            int start = template.indexOf(PREFIX, idx);
            if (start < 0) {
                out.append(template, idx, template.length());
                break;
            }
            out.append(template, idx, start);
            int end = template.indexOf(SUFFIX, start + PREFIX.length());
            if (end < 0) {
                out.append(template, start, template.length());
                break;
            }
            String path = template.substring(start + PREFIX.length(), end).trim();
            out.append(resolve(path, args));
            idx = end + SUFFIX.length();
        }
        return out.toString();
    }

    private static String resolve(String path, JsonNode args) {
        if (WHOLE_INPUT.equals(path)) {
            return args.toString();
        }
        JsonNode node = args;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                throw new InvalidSubRunDefinition("invalid placeholder path: {{" + path + "}}");
            }
            node = node.get(segment.trim());
            // Missing fields no longer abort the whole rendering: fill in an explicit marker and let
            // the skill handle it (it may combine {{input}} to extract the full argument set), so a
            // "missing parameter" does not kill the entire skill.
            if (node == null || node instanceof MissingNode) {
                return "[missing argument: " + path + "]";
            }
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNull()) {
            return "";
        }
        return node.toString();
    }
}