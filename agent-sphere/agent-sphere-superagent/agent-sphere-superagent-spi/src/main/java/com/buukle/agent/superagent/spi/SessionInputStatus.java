package com.buukle.agent.superagent.spi;

/** Lifecycle of a bridged Router input in {@code agent_session_input}. */
public final class SessionInputStatus {

    /** Waiting for the user to answer via the DM card or plain text. */
    public static final String PENDING = "PENDING";

    /** The user answered/approved; the Router run was resumed. */
    public static final String ANSWERED = "ANSWERED";

    /** The run finished or dismissed the input without an answer. */
    public static final String CANCELLED = "CANCELLED";

    private SessionInputStatus() {
    }
}
