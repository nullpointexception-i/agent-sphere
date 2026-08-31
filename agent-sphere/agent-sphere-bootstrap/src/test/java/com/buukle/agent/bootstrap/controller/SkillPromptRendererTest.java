package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.skill.InvalidSkillDefinition;
import com.buukle.agent.common.skill.SkillPromptRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPromptRendererTest {

    @Test
    void render_interpolatesStringAndJson() {
        String template = "候选人 {{candidate.name}} 关键词 {{keyword}}，参数 {{input}}";
        String result = SkillPromptRenderer.render(template,
                "{\"candidate\":{\"name\":\"张三\"},\"keyword\":\"Python\"}");
        assertTrue(result.contains("张三"));
        assertTrue(result.contains("Python"));
        assertTrue(result.contains("\"candidate\""));
    }

    @Test
    void render_missingFieldThrows() {
        assertThrows(InvalidSkillDefinition.class,
                () -> SkillPromptRenderer.render("名字 {{name}}", "{\"other\":1}"));
    }

    @Test
    void render_noPlaceholderReturnsAsIs() {
        assertEquals("plain text", SkillPromptRenderer.render("plain text", "{}"));
    }
}