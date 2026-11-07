package com.buukle.agent.instance.dtvo.enums;

import com.buukle.agent.common.event.EventType;

/**
 * Persistent run states shared by the instance and runtime modules.
 *
 * <p>Values are stored in {@code agent_run.status} and are also used as runtime event types.
 * Keep the names stable: they are part of the database and SSE contract.
 */
public enum RunStatus implements EventType {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    AWAITING_USER,
    /**
     * Wait timer elapsed (wait tool suspension). It is kept separate from {@code AWAITING_USER}
     * because the former waits for a person while the latter waits for the clock: sharing one
     * status would make the frontend show "waiting 90 seconds" as "waiting for your answer",
     * and would also unlock the input box early.
     */
    WAITING;

    /**
     * Compile-time string constants for switch labels and query values.
     * Kept in sync with the enum names; the enum remains the canonical type.
     */
    public static final class Codes {
        public static final String PENDING = "PENDING";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";
        public static final String AWAITING_USER = "AWAITING_USER";
        public static final String WAITING = "WAITING";

        private Codes() {
        }
    }

    @Override
    public String value() {
        return name();
    }
}
