package com.buukle.agent.common.event;

/** 无 owner 的中断 run 待恢复。 */
public record InterruptedRunRecoveryEvent(Long sessionId, Long runId, String reason) {
}
