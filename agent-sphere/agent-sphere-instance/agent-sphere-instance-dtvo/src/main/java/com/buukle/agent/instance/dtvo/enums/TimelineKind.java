package com.buukle.agent.instance.dtvo.enums;

import java.util.Set;

/** Display type of a unified Timeline row. */
public enum TimelineKind {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    CLARIFICATION("clarification"),
    TOOL_APPROVAL("tool_approval"),
    SUBAGENT("subagent"),
    /** Context compaction: divider + streaming output of the compaction model. */
    COMPACTION("compaction"),
    RUN_STATUS("run_status"),
    ERROR("error");

    private final String code;

    TimelineKind(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** Display states allowed for this kind (the single source of truth for the vocabulary constraint). */
    public Set<TimelineState> allowedStates() {
        return switch (this) {
            case USER -> Set.of(TimelineState.COMPLETED);
            case ASSISTANT -> Set.of(TimelineState.RUNNING, TimelineState.COMPLETED,
                    TimelineState.FAILED, TimelineState.CANCELLED);
            case TOOL -> Set.of(TimelineState.IN_PROGRESS, TimelineState.SUCCEEDED,
                    TimelineState.FAILED, TimelineState.CANCELLED);
            case CLARIFICATION -> Set.of(TimelineState.PENDING, TimelineState.ANSWERED,
                    TimelineState.CANCELLED, TimelineState.TIMEOUT);
            case TOOL_APPROVAL -> Set.of(TimelineState.PENDING, TimelineState.ANSWERED,
                    TimelineState.CANCELLED);
            case SUBAGENT -> Set.of(TimelineState.RUNNING, TimelineState.COMPLETED,
                    TimelineState.FAILED, TimelineState.CANCELLED);
            case COMPACTION -> Set.of(TimelineState.RUNNING, TimelineState.COMPLETED,
                    TimelineState.FAILED);
            case RUN_STATUS -> Set.of(TimelineState.RUNNING, TimelineState.WAITING,
                    TimelineState.RESUMED, TimelineState.AWAITING_USER, TimelineState.COMPLETED,
                    TimelineState.FAILED, TimelineState.CANCELLED);
            case ERROR -> Set.of(TimelineState.FAILED);
        };
    }

    public static TimelineKind from(String code) {
        if (code == null) {
            return null;
        }
        for (TimelineKind k : values()) {
            if (k.code.equals(code)) {
                return k;
            }
        }
        return null;
    }
}
