package com.buukle.agent.capability.builtin.tool.textcontains.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.dto.TextContainsExecuteContext;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.enums.TextMatchMode;
import com.buukle.agent.capability.builtin.tool.textcontains.dtvo.vo.TextContainsResultVO;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Determines whether target string B contains substring A.
 *
 * <p><b>Default binding</b>: {@link #needConfig()} returns {@code false}, so the tool is collected
 * by {@code BuiltinToolService#listAutoIncludeBuiltinTools()} and registered unconditionally by
 * {@code ContextPreparer} on **every instance** - no DB capability binding or seed required. This is
 * the opposite of Chrome / WebFetch / WebRead (those return {@code needConfig()==true} and must be
 * bound explicitly in the instance capabilities).
 */
@Slf4j
@Component
public class CapabilityBuiltinToolTextContains implements CapabilityBuiltinToolSpi {

    static final String TOOL_DESCRIPTION = """
            Check whether a target string contains a given substring — a pure, deterministic string test.

            Use it when a task or skill step requires confirming that some text contains a marker
            (e.g. a page body or a fetched document contains a keyword) rather than eyeballing it;
            the answer is exact and costs nothing.

            `needle` is the substring to look for; `haystack` is the text being searched. `needle`
            must not be empty or whitespace-only. `mode` defaults to EXACT (case-sensitive, no
            trimming); use IGNORE_CASE / TRIMMED / IGNORE_CASE_TRIMMED when the input is user- or
            page-derived and case or surrounding whitespace is not meaningful. This tool only
            reports containment — it does not fetch, parse or modify anything.""";

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.TEXT_CONTAINS;
    }

    /** false = default binding: included automatically on all instances, no instance capability binding needed. */
    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolTextContains.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("文本包含判断");
        info.setDisplayNameEn("Text Contains");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(TextContainsExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(TextContainsResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return TextContainsExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return TextContainsResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        TextContainsExecuteContext tc = (TextContainsExecuteContext) ctx;
        String needle = requireNeedle(tc.getNeedle());
        String haystack = requireHaystack(tc.getHaystack());
        TextMatchMode mode = tc.getMode() != null ? tc.getMode() : TextMatchMode.EXACT;

        if (mode == TextMatchMode.TRIMMED || mode == TextMatchMode.IGNORE_CASE_TRIMMED) {
            needle = needle.trim();
            haystack = haystack.trim();
        }
        if (mode == TextMatchMode.IGNORE_CASE || mode == TextMatchMode.IGNORE_CASE_TRIMMED) {
            // Locale.ROOT instead of the default Locale: the latter maps I/i incorrectly in Turkish
            // locales, producing locale-dependent match results
            needle = needle.toLowerCase(Locale.ROOT);
            haystack = haystack.toLowerCase(Locale.ROOT);
        }
        return new TextContainsResultVO(haystack.contains(needle));
    }

    /**
     * Runtime guard.
     *
     * <p>{@code @NotBlank} / {@code @NotNull} only take effect in {@code parameterVerify}, and that
     * path is **not on the execution chain** ({@code ToolExecutor} calls {@code executeBuiltinTool}
     * directly) - relying on the annotations alone, an empty needle would reach
     * {@code String.contains("")} and silently return {@code true}.
     */
    private static String requireNeedle(String needle) {
        if (needle == null || needle.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "needle must not be empty or whitespace-only");
        }
        return needle;
    }

    /** haystack may be empty (an empty target contains no substring, so the answer is false), but missing it is a caller error. */
    private static String requireHaystack(String haystack) {
        if (haystack == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "haystack must not be null");
        }
        return haystack;
    }
}
