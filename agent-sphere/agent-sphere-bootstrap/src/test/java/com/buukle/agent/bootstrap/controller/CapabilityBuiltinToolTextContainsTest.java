package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.dto.TextContainsExecuteContext;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.enums.TextMatchMode;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.vo.TextContainsResultVO;
import com.buukle.agent.capability.builtin.tool.textcontains.tool.CapabilityBuiltinToolTextContains;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `text_contains` 内置工具。
 *
 * <p>两条最关键的护栏：
 * <ul>
 *   <li>{@code needConfig() == false} —— 这是「默认绑定」的**唯一开关**（被
 *       {@code listAutoIncludeBuiltinTools} 收录 → 每个实例自动可用）。改成 true 不会报错，
 *       工具会静默从所有实例消失。</li>
 *   <li>空 needle 必须被拒 —— {@code @NotBlank} 只在 {@code parameterVerify} 里生效，而那条路径
 *       不在运行链路上，光靠注解空串会走到 {@code String.contains("")} 静默返回 true。</li>
 * </ul>
 */
class CapabilityBuiltinToolTextContainsTest {

    private final CapabilityBuiltinToolTextContains tool = new CapabilityBuiltinToolTextContains();

    private static TextContainsExecuteContext ctx(String needle, String haystack, TextMatchMode mode) {
        TextContainsExecuteContext c = new TextContainsExecuteContext();
        c.setNeedle(needle);
        c.setHaystack(haystack);
        c.setMode(mode);
        return c;
    }

    private boolean contains(String needle, String haystack, TextMatchMode mode) {
        return ((TextContainsResultVO) tool.execute(ctx(needle, haystack, mode))).isContains();
    }

    // ---------- EXACT（默认） ----------

    @Test
    void exact_hitsAndMisses() {
        assertTrue(contains("favorite", "the favorite button", TextMatchMode.EXACT));
        assertFalse(contains("favourite", "the favorite button", TextMatchMode.EXACT));
    }

    @Test
    void exact_isCaseSensitive() {
        assertFalse(contains("Proceed", "suggestion=PROCEED", TextMatchMode.EXACT));
    }

    @Test
    void modeNull_defaultsToExact() {
        assertTrue(contains("abc", "xxabcxx", null));
        // 缺省即 EXACT：大小写不同必须不命中
        assertFalse(contains("ABC", "xxabcxx", null));
    }

    // ---------- IGNORE_CASE ----------

    @Test
    void ignoreCase_matchesRegardlessOfCase() {
        assertTrue(contains("Proceed", "suggestion=PROCEED", TextMatchMode.IGNORE_CASE));
        assertTrue(contains("PROCEED", "suggestion=proceed", TextMatchMode.IGNORE_CASE));
    }

    // ---------- TRIMMED ----------

    @Test
    void trimmed_ignoresSurroundingWhitespaceButNotCase() {
        assertTrue(contains("  abc  ", "\n abc \t", TextMatchMode.TRIMMED));
        assertFalse(contains("  ABC  ", "\n abc \t", TextMatchMode.TRIMMED));
    }

    // ---------- IGNORE_CASE_TRIMMED ----------

    @Test
    void ignoreCaseTrimmed_combinesBoth() {
        assertTrue(contains("  ABC  ", "\n abc \t", TextMatchMode.IGNORE_CASE_TRIMMED));
    }

    // ---------- 边界 ----------

    @Test
    void emptyHaystack_isFalseNotAnError() {
        // 空目标串不含任何子串：这是有效结果，不是参数错误
        assertFalse(contains("abc", "", TextMatchMode.EXACT));
    }

    @Test
    void blankNeedle_isRejectedAtRuntime() {
        // 运行时守卫：@NotBlank 在运行链路上不生效，必须自己拦
        assertThrows(BizException.class, () -> contains("", "anything", TextMatchMode.EXACT));
        assertThrows(BizException.class, () -> contains("   ", "anything", TextMatchMode.EXACT));
        assertThrows(BizException.class, () -> contains(null, "anything", TextMatchMode.EXACT));
    }

    @Test
    void nullHaystack_isRejected() {
        assertThrows(BizException.class, () -> contains("abc", null, TextMatchMode.EXACT));
    }

    // ---------- 注册与默认绑定 ----------

    @Test
    void needConfig_isFalse_soToolIsBoundByDefault() {
        assertFalse(tool.needConfig(),
                "needConfig 必须为 false —— 这是「默认绑定」的唯一开关，改成 true 会让工具从所有实例静默消失");
    }

    @Test
    void toolType_isTextContains() {
        assertEquals(BuiltinToolEnum.TEXT_CONTAINS, tool.getToolType());
        assertEquals(11, BuiltinToolEnum.TEXT_CONTAINS.getId());
    }

    @Test
    void getInfo_generatesNonEmptySchemaWithAllParams() {
        var info = tool.getInfo();
        assertNotNull(info.getParamSchema());
        // schema 由 POJO 自动生成：若生成失败会返回 {"error":...}，这里挡住静默失效
        assertTrue(info.getParamSchema().contains("needle"), info.getParamSchema());
        assertTrue(info.getParamSchema().contains("haystack"), info.getParamSchema());
        assertTrue(info.getParamSchema().contains("mode"), info.getParamSchema());
        assertFalse(info.getParamSchema().contains("\"error\""), info.getParamSchema());
        assertEquals("builtin.CapabilityBuiltinToolTextContains", info.getName());
        assertEquals("文本包含判断", info.getDisplayNameCn());
    }

    @Test
    void parameterVerify_rejectsBlankNeedle() {
        assertFalse(tool.parameterVerify("{\"needle\":\"  \",\"haystack\":\"x\"}").isValid());
        assertTrue(tool.parameterVerify("{\"needle\":\"a\",\"haystack\":\"x\"}").isValid());
    }
}
