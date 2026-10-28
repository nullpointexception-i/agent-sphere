package com.buukle.agent.model.service.stream;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 流式响应字段解析的公共小工具。 */
public final class LlmStreamUtils {

    private LlmStreamUtils() {
    }

    /**
     * 按顺序返回第一个「非空文本」字段值。
     *
     * <p>不同网关的 reasoning 字段名不一致（vLLM/Qwen3 用 {@code reasoning}，DeepSeek/智谱用
     * {@code reasoning_content}），且可能同时出现空串；空串不应遮蔽后续字段。
     *
     * @return 第一个非空文本；都没有返回 null
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
