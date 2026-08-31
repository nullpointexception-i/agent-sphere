package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.skill.InvalidSkillDefinition;
import com.buukle.agent.common.skill.SkillDefinition;
import com.buukle.agent.common.skill.SkillDefinitionParser;
import com.buukle.agent.common.skill.ToolRefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                  "promptTemplate": "请围绕 {{keyword}} 完成任务",
                  "allowTools": ["builtin:chrome", "skill:8", "cli:3", "mcp:12:search"]
                }
                """;
        SkillDefinition parsed = SkillDefinitionParser.parse(def);
        assertNotNull(parsed);
        assertEquals(1, parsed.version());
        assertTrue(parsed.parametersSchemaJson().contains("\"keyword\""));
        assertEquals("请围绕 {{keyword}} 完成任务", parsed.promptTemplate());
        assertTrue(parsed.allowToolsSpecified());
        assertEquals(4, parsed.allowTools().size());
        assertTrue(parsed.allowTools().contains("builtin:chrome"));
    }

    @Test
    void parse_legacyPromptFallsBackToNoTools() {
        SkillDefinition parsed = SkillDefinitionParser.parse("{\"prompt\":\"请按配置执行\"}");
        assertNotNull(parsed);
        assertEquals("请按配置执行", parsed.promptTemplate());
        assertTrue(parsed.allowTools().isEmpty());
        assertFalse(parsed.allowToolsSpecified());
    }

    @Test
    void parse_legacyPromptWithMarkdownFence() {
        SkillDefinition parsed = SkillDefinitionParser.parse("```json\n{\"prompt\":\"请按配置执行\"}\n```");
        assertNotNull(parsed);
        assertEquals("请按配置执行", parsed.promptTemplate());
    }

    @Test
    void parse_missingParametersThrows() {
        assertThrows(InvalidSkillDefinition.class,
                () -> SkillDefinitionParser.parse("{\"promptTemplate\":\"todo\"}"));
    }

    @Test
    void parse_missingPromptTemplateThrows() {
        assertThrows(InvalidSkillDefinition.class,
                () -> SkillDefinitionParser.parse("{\"parameters\":{\"type\":\"object\"}}"));
    }

    @Test
    void parse_invalidJsonThrows() {
        assertThrows(InvalidSkillDefinition.class, () -> SkillDefinitionParser.parse("not json"));
    }

    @Test
    void parse_emptyReturnsNull() {
        assertNull(SkillDefinitionParser.parse(null));
        assertNull(SkillDefinitionParser.parse(""));
    }

    @Test
    void parse_invalidAllowToolsRefThrows() {
        assertThrows(InvalidSkillDefinition.class,
                () -> SkillDefinitionParser.parse("""
                        {"parameters":{"type":"object"},"promptTemplate":"p","allowTools":["???:1"]}
                        """));
    }

    @Test
    void parse_wildcardAllowToolsAllowed() {
        SkillDefinition parsed = SkillDefinitionParser.parse(
                "{\"parameters\":{\"type\":\"object\"},\"promptTemplate\":\"p\",\"allowTools\":[\"*\"]}");
        assertNotNull(parsed);
        assertTrue(parsed.allowTools().contains(ToolRefs.WILDCARD));
    }
}