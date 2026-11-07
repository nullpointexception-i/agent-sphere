package com.buukle.agent.channel.spi;

import java.io.Serializable;

/** Result of persisting an event. */
public record IngestResult(boolean accepted, Long eventId, Long threadId, boolean duplicate)
        implements Serializable {

    public static IngestResult accepted(Long eventId, Long threadId) {
        return new IngestResult(true, eventId, threadId, false);
    }

    public static IngestResult duplicate(Long eventId, Long threadId) {
        return new IngestResult(true, eventId, threadId, true);
    }

    public static IngestResult rejected() {
        return new IngestResult(false, null, null, false);
    }
}
