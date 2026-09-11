package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.common.skill.SkillDefinition;
import com.buukle.agent.common.skill.SkillDefinitionParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDefinitionParserTest {

    @Test
    void parse_v1FullDefinition() {
        String def = """
                {
                  "version": 1,
                  "parameters": {"type":"object","properties":{"keyword":{"type":"string"}},"required":["keyword"]},
                  "promptTemplate": "请围绕 {{keyword}} 完成任务"
                }
                """;
        SkillDefinition parsed = SkillDefinitionParser.parse(def);
        assertNotNull(parsed);
        assertEquals(1, parsed.version());
        assertTrue(parsed.parametersSchemaJson().contains("\"keyword\""));
        assertEquals("请围绕 {{keyword}} 完成任务", parsed.promptTemplate());
    }

    @Test
    void parse_legacyPromptFallsBack() {
        SkillDefinition parsed = SkillDefinitionParser.parse("{\"prompt\":\"请按配置执行\"}");
        assertNotNull(parsed);
        assertEquals("请按配置执行", parsed.promptTemplate());
    }

    @Test
    void parse_legacyPromptWithMarkdownFence() {
        SkillDefinition parsed = SkillDefinitionParser.parse("```json\n{\"prompt\":\"请按配置执行\"}\n```");
        assertNotNull(parsed);
        assertEquals("请按配置执行", parsed.promptTemplate());
    }

    @Test
    void parse_missingParametersThrows() {
        assertThrows(InvalidSubRunDefinition.class,
                () -> SkillDefinitionParser.parse("{\"promptTemplate\":\"todo\"}"));
    }

    @Test
    void parse_missingPromptTemplateThrows() {
        assertThrows(InvalidSubRunDefinition.class,
                () -> SkillDefinitionParser.parse("{\"parameters\":{\"type\":\"object\"}}"));
    }

    @Test
    void parse_invalidJsonThrows() {
        assertThrows(InvalidSubRunDefinition.class, () -> SkillDefinitionParser.parse("not json"));
    }

    @Test
    void parse_emptyReturnsNull() {
        assertNull(SkillDefinitionParser.parse(null));
        assertNull(SkillDefinitionParser.parse(""));
    }
}
