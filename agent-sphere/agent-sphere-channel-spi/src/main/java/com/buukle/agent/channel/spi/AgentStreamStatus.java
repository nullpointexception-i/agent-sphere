package com.buukle.agent.channel.spi;

/** Run lifecycle stream states carried by {@link AgentStreamEvent}. */
public final class AgentStreamStatus {

    /** The run has been submitted and is starting. */
    public static final String STARTED = "started";

    /** The run finished successfully; the authoritative reply follows. */
    public static final String COMPLETED = "completed";

    /** The run failed; {@link AgentStreamEvent#errorMessage()} carries the reason. */
    public static final String FAILED = "failed";

    /** The run was cancelled by the user or the platform. */
    public static final String CANCELLED = "cancelled";

    /** Tool-call lifecycle statuses (phase=status). */
    public static final String TOOL_PENDING = "tool_pending";
    public static final String TOOL_SUCCEEDED = "tool_succeeded";
    public static final String TOOL_FAILED = "tool_failed";

    /**
     * The run parked in AWAITING_USER (clarification / tool approval). The bubble
     * pauses here; a card message in the DM asked the user to respond. Non-terminal:
     * the same runId resumes later and re-emits {@link #STARTED}.
     */
    public static final String AWAITING_USER = "awaiting_user";

    private AgentStreamStatus() {
    }
}
