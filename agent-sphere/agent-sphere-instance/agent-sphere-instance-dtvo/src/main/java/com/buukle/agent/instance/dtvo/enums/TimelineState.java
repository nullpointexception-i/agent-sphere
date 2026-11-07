package com.buukle.agent.instance.dtvo.enums;

/**
 * Display state of a unified Timeline row.
 *
 * <p>Used for rendering only and <b>does not participate in any flow control</b>; control decisions
 * always read the source tables ({@code agent_run} / {@code agent_llm_interaction_record} / tool /
 * clarification / approval).
 *
 * <p>Each kind allows only one subset, see {@link TimelineKind#allowedStates()}.
 * The vocabulary is uniformly upper-case and no longer accepts historical spellings such as
 * {@code started} / {@code succeeded}.
 */
public enum TimelineState {
    PENDING("PENDING"),
    RUNNING("RUNNING"),
    WAITING("WAITING"),
    /** wait elapsed and resumed: turns the "waiting" row into past tense while still allowing a terminal row to be opened separately. */
    RESUMED("RESUMED"),
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

    /** Whether this state is allowed on the given kind. */
    public boolean isValidFor(TimelineKind kind) {
        return kind != null && kind.allowedStates().contains(this);
    }

    /** Terminal-state check (for display). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED
                || this == TIMEOUT || this == ANSWERED || this == SUCCEEDED;
    }

    /**
     * Strict parsing: only the enum code is accepted; mismatched casing or legacy spellings return null.
     * Historical data is normalized once by the V111 migration.
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
