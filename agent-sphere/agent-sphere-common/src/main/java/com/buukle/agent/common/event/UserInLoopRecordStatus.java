package com.buukle.agent.common.event;

/**
 * Status values for {@code agent_user_in_loop_record.status} and its lifecycle events.
 *
 * <p>This is the single source: the persistence layer writes {@link #value()} and the runtime
 * publishes the enum itself as an {@link EventType}.
 */
public enum UserInLoopRecordStatus implements EventType {
    WAITING,
    RESPONDED;

    @Override
    public String value() {
        return name();
    }
}
