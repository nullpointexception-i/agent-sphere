package com.buukle.agent.common.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * {@code {{path}}} 占位符渲染器（安全：不执行表达式/脚本/SpEL/Shell）。
 * 支持嵌套路径（{{candidate.name}}）、对象/数组自动 JSON 序列化；缺失字段抛明确错误。
 */
public final class SkillPromptRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "{{";
    private static final String SUFFIX = "}}";
    private static final String WHOLE_INPUT = "input";

    private SkillPromptRenderer() {
    }

    /** 渲染模板，argsJson 为 skill 调用参数 JSON。 */
    public static String render(String template, String argsJson) {
        if (template == null || template.isBlank()) {
            return "";
        }
        JsonNode args;
        try {
            args = JSON.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            throw new InvalidSkillDefinition("skill 参数不是合法 JSON: " + e.getMessage());
        }
        if (args == null || !args.isObject()) {
            throw new InvalidSkillDefinition("skill 参数必须是 JSON 对象");
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
                throw new InvalidSkillDefinition("占位符路径非法: {{" + path + "}}");
            }
            node = node.get(segment.trim());
            if (node == null || node instanceof MissingNode) {
                throw new InvalidSkillDefinition("skill 参数缺少字段: " + path);
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