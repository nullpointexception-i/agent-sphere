package com.buukle.agent.util.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具调用理由（保留参数 {@link #REASON_KEY}）的读取与剥离。
 *
 * <p>模型在 tool_call.arguments 里额外携带 {@code _reason}，用于前端展示「为什么调用」。
 * 该字段不是任何业务工具的入参，必须在执行前剥离；同时保留在原始 arguments_json 中供审计。
 */
public final class ToolCallReason {

    /** 工具 schema 中的保留参数名，避免与 MCP/业务工具自己的 reason 参数冲突。 */
    public static final String REASON_KEY = "_reason";
    /** 理由最大长度（字符）。 */
    public static final int MAX_REASON_CHARS = 200;
    /** 注入到每个工具 schema 的描述。 */
    public static final String SCHEMA_DESCRIPTION =
            "Short user-visible reason for calling this tool now. "
                    + "Do not include hidden chain-of-thought or sensitive data.";

    private ToolCallReason() {
    }

    /** 提取并规范化理由；缺失/非法 JSON 返回 null。 */
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

    /** 去掉保留参数；没有该字段或 JSON 非法时原样返回。 */
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

    /** 压成单行、去首尾空白并截断；空白返回 null。 */
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
