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
 * The `text_contains` builtin tool.
 *
 * <p>Two critical guards:
 * <ul>
 *   <li>{@code needConfig() == false} — the **only switch** for "default binding" (being
 *       picked up by {@code listAutoIncludeBuiltinTools} makes it available on every instance). Changing it to true raises no error,
 *       but the tool silently disappears from every instance.</li>
 *   <li>An empty needle must be rejected — {@code @NotBlank} only applies in {@code parameterVerify}, and that path
 *       is not on the runtime path, so the annotation alone lets an empty string reach {@code String.contains("")} and silently return true.</li>
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

    // ---------- EXACT (default) ----------

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
        // The default is EXACT: different case must not match
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

    // ---------- Edge cases ----------

    @Test
    void emptyHaystack_isFalseNotAnError() {
        // An empty haystack contains no substring: that is a valid result, not a parameter error
        assertFalse(contains("abc", "", TextMatchMode.EXACT));
    }

    @Test
    void blankNeedle_isRejectedAtRuntime() {
        // Runtime guard: @NotBlank does not apply on the runtime path, so we must enforce it ourselves
        assertThrows(BizException.class, () -> contains("", "anything", TextMatchMode.EXACT));
        assertThrows(BizException.class, () -> contains("   ", "anything", TextMatchMode.EXACT));
        assertThrows(BizException.class, () -> contains(null, "anything", TextMatchMode.EXACT));
    }

    @Test
    void nullHaystack_isRejected() {
        assertThrows(BizException.class, () -> contains("abc", null, TextMatchMode.EXACT));
    }

    // ---------- Registration and default binding ----------

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
        // The schema is generated from the POJO: on failure it returns {"error":...}; this blocks silent breakage
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
