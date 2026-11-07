package com.buukle.agent.util.markdown;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * config → Markdown rendering.
 *
 * <p>This guards against two regressions: rendering falling back to JSON (quotes and braces make
 * the model read instructions as data), and blank sections not being skipped (noise such as
 * {@code hardGates: null} showing up in the prompt).
 */
class MarkdownRendererTest {

    @Test
    void rendersSectionListWithTitleAndBody() {
        Map<String, Object> section1 = new LinkedHashMap<>();
        section1.put("title", "第一步：打开目标渠道");
        section1.put("body", "顺次打开 boss");
        Map<String, Object> section2 = new LinkedHashMap<>();
        section2.put("title", "硬性门槛（必要条件，任一不满足即不得收藏）");
        section2.put("body", "学历符合其一：bachelor；期望薪资 ≥ 30K。");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sections", List.of(section1, section2));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertTrue(markdown.contains("### 第一步：打开目标渠道"), markdown);
        assertTrue(markdown.contains("顺次打开 boss"), markdown);
        assertTrue(markdown.contains("### 硬性门槛（必要条件，任一不满足即不得收藏）"), markdown);
        // must not emit an extra "sections" wrapper heading
        assertFalse(markdown.contains("sections"), markdown);
        // no JSON structure may remain
        assertFalse(markdown.contains("{"), markdown);
        assertFalse(markdown.contains("\"title\""), markdown);
    }

    @Test
    void skipsBlankSections() {
        Map<String, Object> blankBody = new LinkedHashMap<>();
        blankBody.put("title", "岗位画像匹配要求");
        blankBody.put("body", null);
        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("title", "收藏规则");
        keep.put("body", "① 不达标一律不收藏");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sections", List.of(blankBody, keep));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertFalse(markdown.contains("岗位画像匹配要求"), markdown);
        assertTrue(markdown.contains("### 收藏规则"), markdown);
    }

    @Test
    void fallsBackToGenericRenderingForNonSectionConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("step1", "顺次打开 boss");
        config.put("hardGates", null);
        config.put("nested", Map.of("inner", "值"));
        config.put("items", List.of("a", "b"));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertTrue(markdown.contains("### step1"), markdown);
        assertTrue(markdown.contains("顺次打开 boss"), markdown);
        // null values skip the whole section
        assertFalse(markdown.contains("hardGates"), markdown);
        // nested objects drop one level; lists become list items
        assertTrue(markdown.contains("#### inner"), markdown);
        assertTrue(markdown.contains("- a"), markdown);
    }

    @Test
    void sectionListWithMissingTitleIsNotTreatedAsSections() {
        // A missing title means this is not the section convention; fall back to generic rendering
        // rather than dropping the content.
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sections", List.of(Map.of("body", "没有标题")));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertTrue(markdown.contains("### sections"), markdown);
        assertTrue(markdown.contains("没有标题"), markdown);
    }

    @Test
    void emptyOrNullConfigRendersEmpty() {
        assertEquals("", MarkdownRenderer.renderConfig(null));
        assertEquals("", MarkdownRenderer.renderConfig(Map.of()));
    }
}
