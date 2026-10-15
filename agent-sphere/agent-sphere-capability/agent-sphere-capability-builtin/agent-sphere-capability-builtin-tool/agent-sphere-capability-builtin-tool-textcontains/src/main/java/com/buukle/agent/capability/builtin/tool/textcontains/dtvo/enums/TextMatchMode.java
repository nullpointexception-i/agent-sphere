package com.buukle.agent.capability.builtin.tool.textcontains.dtvo.enums;

/**
 * `text_contains` 的匹配模式。
 *
 * <p>刻意做成显式枚举而非若干布尔开关：布尔组合会让「区分大小写」与「去空白」产生四个含义不明的
 * 开关状态，而枚举在参数 schema 里会直接列成可选值，模型不需要推断。
 */
public enum TextMatchMode {

    /** 严格子串匹配（默认）：区分大小写、不做任何归一化。 */
    EXACT,

    /** 忽略大小写。 */
    IGNORE_CASE,

    /** 两端去空白后严格匹配。 */
    TRIMMED,

    /** 两端去空白 + 忽略大小写。 */
    IGNORE_CASE_TRIMMED
}
