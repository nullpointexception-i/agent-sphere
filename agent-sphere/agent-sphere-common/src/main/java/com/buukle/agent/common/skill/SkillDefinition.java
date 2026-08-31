package com.buukle.agent.common.skill;

import java.util.List;

/**
 * Skill V1 定义（解析后的 typed model）。
 *
 * @param version               定义版本（当前 1）
 * @param parametersSchemaJson  LLM 调用 skill 工具时的入参 JSON Schema
 * @param promptTemplate        嵌套 ReAct 子 Agent 的任务指令模板
 * @param allowTools            子 Agent 允许使用的工具白名单（缺省/空 = 不允许任何工具）
 * @param allowToolsSpecified   是否显式声明了 allowTools（区分"未声明"与"声明为空"）
 */
public record SkillDefinition(
        int version,
        String parametersSchemaJson,
        String promptTemplate,
        List<String> allowTools,
        boolean allowToolsSpecified) {

    public static SkillDefinition ofLegacy(String prompt) {
        return new SkillDefinition(1, DEFAULT_EMPTY_PARAMETERS_SCHEMA, prompt, List.of(), false);
    }

    public static final String DEFAULT_EMPTY_PARAMETERS_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
}