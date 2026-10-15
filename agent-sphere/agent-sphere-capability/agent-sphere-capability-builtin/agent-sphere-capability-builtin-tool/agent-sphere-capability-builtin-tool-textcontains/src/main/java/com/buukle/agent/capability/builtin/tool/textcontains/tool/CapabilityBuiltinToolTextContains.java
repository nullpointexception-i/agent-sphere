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
 * 判断目标字符串 B 是否包含子串 A。
 *
 * <p><b>默认绑定</b>：{@link #needConfig()} 返回 {@code false}，因此被
 * {@code BuiltinToolService#listAutoIncludeBuiltinTools()} 收录，由 {@code ContextPreparer}
 * 在**每个实例**上无条件注册 —— 无需任何 DB 能力绑定或 seed。这与 Chrome / WebFetch / WebRead
 * 相反（那三个 {@code needConfig()==true}，必须在实例能力里显式绑定）。
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

    /** false = 默认绑定：自动包含在所有实例上，无需实例能力绑定。 */
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
            // Locale.ROOT 而非默认 Locale：后者在土耳其语环境会把 I/i 映射错，产生语言相关的匹配结果
            needle = needle.toLowerCase(Locale.ROOT);
            haystack = haystack.toLowerCase(Locale.ROOT);
        }
        return new TextContainsResultVO(haystack.contains(needle));
    }

    /**
     * 运行时守卫。
     *
     * <p>{@code @NotBlank} / {@code @NotNull} 只在 {@code parameterVerify} 里生效，而那条路径
     * **不在运行链路上**（{@code ToolExecutor} 直接调 {@code executeBuiltinTool}）—— 光靠注解，
     * 空 needle 会一路走到 {@code String.contains("")} 并静默返回 {@code true}。
     */
    private static String requireNeedle(String needle) {
        if (needle == null || needle.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "needle 不能为空或纯空白");
        }
        return needle;
    }

    /** haystack 允许为空串（空目标串不含任何子串，答案是 false），但缺失是调用错误。 */
    private static String requireHaystack(String haystack) {
        if (haystack == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "haystack 不能为 null");
        }
        return haystack;
    }
}
