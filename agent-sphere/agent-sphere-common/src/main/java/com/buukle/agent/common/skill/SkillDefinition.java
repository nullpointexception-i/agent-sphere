package com.buukle.agent.common.skill;

/**
 * Skill V1 定义（解析后的 typed model）。
 *
 * @param version               定义版本（当前 1）
 * @param parametersSchemaJson  LLM 调用 skill 工具时的入参 JSON Schema
 * @param promptTemplate        子 Agent 的任务指令模板
 */
public record SkillDefinition(
        int version,
        String parametersSchemaJson,
        String promptTemplate) {

    public static SkillDefinition ofLegacy(String prompt) {
        return new SkillDefinition(1, DEFAULT_EMPTY_PARAMETERS_SCHEMA, prompt);
    }

    public static final String DEFAULT_EMPTY_PARAMETERS_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
}
