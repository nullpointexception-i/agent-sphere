package com.buukle.agent.common.constant;

import java.util.List;

/**
 * Unified reasoning-effort levels.
 *
 * <p>Each provider uses a different underlying field (reasoning_effort / thinking /
 * enable_thinking); these level names are the single external vocabulary, and the actual mapping
 * happens in ProviderStrategy.
 */
public final class ReasoningEffort {

    /** Reasoning off (not an effort level; expressed separately by the UI). */
    public static final String NONE = "none";
    public static final String MINIMAL = "minimal";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
    public static final String XHIGH = "xhigh";
    public static final String MAX = "max";

    /** Low to high; used to rank the "highest feasible level". */
    public static final List<String> ORDER = List.of(
            NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX);

    /** Fallback range when a provider declares no levels. */
    public static final List<String> DEFAULT_LEVELS = List.of(LOW, MEDIUM, HIGH, MAX);

    private ReasoningEffort() {
    }
}
