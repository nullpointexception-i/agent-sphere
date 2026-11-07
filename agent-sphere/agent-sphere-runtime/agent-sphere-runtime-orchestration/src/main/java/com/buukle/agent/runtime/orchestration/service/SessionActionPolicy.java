package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.instance.dtvo.enums.RunStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Send-lock decision: the single system-wide entry point.
 *
 * <p>The timeline ({@code agent_timeline}) is display-only and takes no part in any decision here.
 * The frontend, widget and any future entry point must call the same rule set, so implementations cannot drift apart.
 */
public final class SessionActionPolicy {

    public static final String BLOCK_RUNNING = "RUNNING";
    public static final String BLOCK_CLARIFICATION = "CLARIFICATION";
    public static final String BLOCK_TOOL_APPROVAL = "TOOL_APPROVAL";

    private SessionActionPolicy() {
    }

    public record Decision(boolean canSend, List<String> blockReasons) {
    }

    /**
     * @param runStatus             active run status, may be null
     * @param pendingClarifications number of unanswered clarifications
     * @param pendingToolApprovals  number of unhandled approvals
     */
    public static Decision decide(String runStatus, int pendingClarifications, int pendingToolApprovals) {
        List<String> reasons = new ArrayList<>(3);
        if (RunStatus.PENDING.name().equals(runStatus)
                || RunStatus.RUNNING.name().equals(runStatus)
                || RunStatus.WAITING.name().equals(runStatus)) {
            reasons.add(BLOCK_RUNNING);
        }
        if (pendingClarifications > 0) {
            reasons.add(BLOCK_CLARIFICATION);
        }
        if (pendingToolApprovals > 0) {
            reasons.add(BLOCK_TOOL_APPROVAL);
        }
        return new Decision(reasons.isEmpty(), List.copyOf(reasons));
    }
}
