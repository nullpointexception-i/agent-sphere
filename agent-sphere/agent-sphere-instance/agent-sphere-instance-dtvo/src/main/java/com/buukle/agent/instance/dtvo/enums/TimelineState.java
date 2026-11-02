package com.buukle.agent.instance.dtvo.enums;

/**
 * 统一 Timeline 行展示状态。
 *
 * <p>只用于渲染，<b>不参与任何流程控制</b>；控制判定一律读来源表
 * （{@code agent_run} / {@code agent_llm_interaction_record} / 工具 / 澄清 / 审批）。
 *
 * <p>每个 kind 只允许一个子集，见 {@link TimelineKind#allowedStates()}。
 * 词表统一为大写，不再接受 {@code started} / {@code succeeded} 等历史写法。
 */
public enum TimelineState {
    PENDING("PENDING"),
    RUNNING("RUNNING"),
    WAITING("WAITING"),
    AWAITING_USER("AWAITING_USER"),
    IN_PROGRESS("IN_PROGRESS"),
    SUCCEEDED("SUCCEEDED"),
    ANSWERED("ANSWERED"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
    TIMEOUT("TIMEOUT");

    private final String code;

    TimelineState(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 该状态是否允许出现在指定 kind 上。 */
    public boolean isValidFor(TimelineKind kind) {
        return kind != null && kind.allowedStates().contains(this);
    }

    /** 终态判定（展示用）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED
                || this == TIMEOUT || this == ANSWERED || this == SUCCEEDED;
    }

    /**
     * 严格解析：只认枚举 code，大小写不一致或历史写法一律返回 null。
     * 历史数据由 V111 迁移一次性归一。
     */
    public static TimelineState from(String code) {
        if (code == null) {
            return null;
        }
        for (TimelineState s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }
}
