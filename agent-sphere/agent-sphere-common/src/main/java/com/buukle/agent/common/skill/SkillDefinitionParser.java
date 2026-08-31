package com.buukle.agent.common.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill definition 解析器。支持 V1（parameters + promptTemplate + allowTools）与遗留
 * {"prompt": "..."} 格式；不再"日志后静默跳过"，解析失败抛出 {@link InvalidSkillDefinition}。
 */
public final class SkillDefinitionParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_VERSION = "version";
    private static final String KEY_PARAMETERS = "parameters";
    private static final String KEY_PROMPT_TEMPLATE = "promptTemplate";
    private static final String KEY_ALLOW_TOOLS = "allowTools";
    private static final String KEY_PROMPT = "prompt";
    private static final String MARKDOWN_FENCE = "```json";

    private SkillDefinitionParser() {
    }

    /** 解析定义；返回 null 仅当入参为 null/空白（缺省定义），其余失败抛异常。 */
    public static SkillDefinition parse(String definition) throws InvalidSkillDefinition {
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
            throw new InvalidSkillDefinition("definition 不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new InvalidSkillDefinition("definition 必须是 JSON 对象");
        }
        // 遗留格式：{"prompt": "..."}
        JsonNode legacyPrompt = root.get(KEY_PROMPT);
        if (legacyPrompt != null && root.size() == 1 && legacyPrompt.isTextual()) {
            return SkillDefinition.ofLegacy(legacyPrompt.asText());
        }
        JsonNode params = root.get(KEY_PARAMETERS);
        JsonNode promptTemplate = root.get(KEY_PROMPT_TEMPLATE);
        if (params == null || !params.isObject()) {
            throw new InvalidSkillDefinition("缺少 'parameters' JSON Schema 对象");
        }
        if (promptTemplate == null || !promptTemplate.isTextual() || promptTemplate.asText().isBlank()) {
            throw new InvalidSkillDefinition("缺少非空 'promptTemplate'");
        }
        List<String> allowTools = parseAllowTools(root.get(KEY_ALLOW_TOOLS));
        int version = root.has(KEY_VERSION) && root.get(KEY_VERSION).isInt() ? root.get(KEY_VERSION).asInt() : 1;
        return new SkillDefinition(version, params.toString(), promptTemplate.asText(), allowTools, root.has(KEY_ALLOW_TOOLS));
    }

    private static List<String> parseAllowTools(JsonNode node) throws InvalidSkillDefinition {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.isEmpty()) {
            return List.of();
        }
        ArrayNode array = (ArrayNode) node;
        List<String> refs = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new InvalidSkillDefinition("'allowTools' 必须是字符串数组");
            }
            String ref = item.asText().trim();
            if (ref.isEmpty()) {
                continue;
            }
            ToolRefs.validate(ref);
            refs.add(ref);
        }
        return List.copyOf(refs);
    }
}