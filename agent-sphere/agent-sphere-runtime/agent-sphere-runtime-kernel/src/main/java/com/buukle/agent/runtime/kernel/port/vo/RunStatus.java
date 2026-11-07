package com.buukle.agent.runtime.kernel.port.vo;

public enum RunStatus implements EventType {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, AWAITING_USER,
    /**
     * Wait timer elapsed (wait tool suspension). It is kept separate from {@code AWAITING_USER} because the former waits for a person
     * while the latter waits for the clock: sharing one status would make the frontend show "waiting 90 seconds" as "waiting for your answer",
     * and would also unlock the input box early.
     */
    WAITING;

    @Override
    public String value() {
        return name();
    }
}
