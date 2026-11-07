package com.buukle.agent.common.skill;

/**
 * Skill V1 definition (parsed typed model).
 *
 * @param version               definition version (currently 1)
 * @param parametersSchemaJson  JSON Schema for the arguments when the LLM calls the skill tool
 * @param promptTemplate        task instruction template for the sub-agent
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
