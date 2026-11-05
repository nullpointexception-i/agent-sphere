package com.buukle.agent.common.context;

import org.slf4j.MDC;

/** MDC keys for Super-Agent background processing. */
public final class SuperAgentTraceContext {

    public static final String EVENT_ID = "eventId";
    public static final String THREAD_ID = "threadId";
    public static final String SUPER_AGENT_ID = "superAgentId";

    private SuperAgentTraceContext() {
    }

    public static void put(Long eventId, Long threadId, Long superAgentId) {
        if (eventId != null) {
            MDC.put(EVENT_ID, String.valueOf(eventId));
        }
        if (threadId != null) {
            MDC.put(THREAD_ID, String.valueOf(threadId));
        }
        if (superAgentId != null) {
            MDC.put(SUPER_AGENT_ID, String.valueOf(superAgentId));
        }
    }

    public static void clear() {
        MDC.remove(EVENT_ID);
        MDC.remove(THREAD_ID);
        MDC.remove(SUPER_AGENT_ID);
    }
}
