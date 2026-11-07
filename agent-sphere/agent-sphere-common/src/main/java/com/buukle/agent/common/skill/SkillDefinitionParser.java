package com.buukle.agent.common.skill;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Skill definition parser. Supports V1 (parameters + promptTemplate) and the legacy
 * {"prompt": "..."} format; it no longer "logs and silently skips" — a parse failure throws
 * {@link InvalidSubRunDefinition}. Unknown keys are ignored (only version / parameters /
 * promptTemplate / prompt are read).
 */
public final class SkillDefinitionParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_VERSION = "version";
    private static final String KEY_PARAMETERS = "parameters";
    private static final String KEY_PROMPT_TEMPLATE = "promptTemplate";
    private static final String KEY_PROMPT = "prompt";
    private static final String MARKDOWN_FENCE = "```json";

    private SkillDefinitionParser() {
    }

    /** Parses the definition; returns null only when the input is null/blank (no definition), any other failure throws. */
    public static SkillDefinition parse(String definition) throws InvalidSubRunDefinition {
        if (definition == null || definition.isBlank()) {
            return null;
        }
        String jsonStr = definition;
        int jsonStart = definition.indexOf(MARKDOWN_FENCE);
        if (jsonStart >= 0) {
            int contentStart = jsonStart + MARKDOWN_FENCE.length();
            int jsonEnd = definition.indexOf("```", contentStart);
            jsonStr = jsonEnd >= 0 ? definition.substring(contentStart, jsonEnd).trim() : jsonStr;
        }
        JsonNode root;
        try {
            root = JSON.readTree(jsonStr);
        } catch (Exception e) {
            throw new InvalidSubRunDefinition("definition is not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new InvalidSubRunDefinition("definition must be a JSON object");
        }
        // legacy format: {"prompt": "..."}
        JsonNode legacyPrompt = root.get(KEY_PROMPT);
        if (legacyPrompt != null && root.size() == 1 && legacyPrompt.isTextual()) {
            return SkillDefinition.ofLegacy(legacyPrompt.asText());
        }
        JsonNode params = root.get(KEY_PARAMETERS);
        JsonNode promptTemplate = root.get(KEY_PROMPT_TEMPLATE);
        if (params == null || !params.isObject()) {
            throw new InvalidSubRunDefinition("missing 'parameters' JSON Schema object");
        }
        if (promptTemplate == null || !promptTemplate.isTextual() || promptTemplate.asText().isBlank()) {
            throw new InvalidSubRunDefinition("missing non-blank 'promptTemplate'");
        }
        int version = root.has(KEY_VERSION) && root.get(KEY_VERSION).isInt() ? root.get(KEY_VERSION).asInt() : 1;
        return new SkillDefinition(version, params.toString(), promptTemplate.asText());
    }
}
