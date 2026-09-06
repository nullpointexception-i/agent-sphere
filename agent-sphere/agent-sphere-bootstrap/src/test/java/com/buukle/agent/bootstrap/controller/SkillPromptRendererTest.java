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
    void render_missingFieldIsMarkedNotFatal() {
        // 缺字段不再中止整个 skill：回填显式标记，供 skill 结合 {{input}} 自抽取。
        String result = SkillPromptRenderer.render(
                "```json\n{{input}}\n``` 频道={{channel_url}}", "{\"other\":1}");
        assertTrue(result.contains("[缺参数:channel_url]"));
        assertTrue(result.contains("\"other\":1"));
    }

    @Test
    void render_invalidPathStillThrows() {
        assertThrows(InvalidSkillDefinition.class,
                () -> SkillPromptRenderer.render("名字 {{}}", "{\"name\":\"x\"}"));
    }

    @Test
    void render_noPlaceholderReturnsAsIs() {
        assertEquals("plain text", SkillPromptRenderer.render("plain text", "{}"));
    }
}