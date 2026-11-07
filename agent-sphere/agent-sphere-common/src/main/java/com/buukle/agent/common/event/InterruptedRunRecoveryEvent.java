package com.buukle.agent.common.event;

/** An interrupted run without an owner is pending recovery. */
public record InterruptedRunRecoveryEvent(Long sessionId, Long runId, String reason) {
}
