package com.buukle.agent.common.constant;

import java.util.List;

/**
 * 统一思考强度档位。
 *
 * <p>各厂商底层字段不同（reasoning_effort / thinking / enable_thinking），
 * 统一使用这里的档位名对外表达；具体映射在 ProviderStrategy 中完成。
 */
public final class ReasoningEffort {

    /** 关闭思考（不是强度档位，前端单独表达）。 */
    public static final String NONE = "none";
    public static final String MINIMAL = "minimal";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
    public static final String XHIGH = "xhigh";
    public static final String MAX = "max";

    /** 从低到高；用于“最高可行档”排序。 */
    public static final List<String> ORDER = List.of(
            NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX);

    /** 厂商未声明档位时的兜底范围。 */
    public static final List<String> DEFAULT_LEVELS = List.of(LOW, MEDIUM, HIGH, MAX);

    private ReasoningEffort() {
    }
}
