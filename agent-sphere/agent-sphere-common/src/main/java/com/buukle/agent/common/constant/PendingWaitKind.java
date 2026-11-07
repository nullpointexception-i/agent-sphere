package com.buukle.agent.common.constant;

/**
 * Values of {@code agent_pending_wait.kind}.
 *
 * <p>Lives in common because three places need it: kernel (triggers the suspension),
 * instance-service (persistence/query) and orchestration (wake branches), and they only meet in
 * common.
 */
public final class PendingWaitKind {

    /** wait builtin tool: on wake, record the tool result and append a synthetic "wait is over" user message. */
    public static final String WAIT = "WAIT";

    /** Graded retry after a transient LLM failure: on wake, rerun the same turn without appending any synthetic message. */
    public static final String LLM_RETRY = "LLM_RETRY";

    private PendingWaitKind() {
    }
}
