package com.buukle.agent.common.eventbus;

/**
 * Redis topic and key-prefix constants for distributed runtime state / the event bus (common layer,
 * shared by infrastructure and runtime-kernel). Unified prefix {@code runtime:}; keys are produced by
 * static methods so call sites never concatenate strings or use magic values.
 */
public final class DistributedRuntimeConstants {

    /** Unified key prefix. */
    public static final String KEY_PREFIX = "runtime:";

    // ---- topics (Redis pub/sub) ----
    public static final String TOPIC_EVENTS = "runtime.events";
    public static final String TOPIC_AGUI = "runtime.agui";
    public static final String TOPIC_CHROME_COMMAND = "runtime.chrome.command";
    public static final String TOPIC_CHROME_CALLBACK = "runtime.chrome.callback";

    // ---- key prefixes ----
    public static final String KEY_LOCK_PREFIX = KEY_PREFIX + "lock:";
    public static final String KEY_STATE_PREFIX = KEY_PREFIX + "state:";
    /** Redis Set of sessions that currently have runtime state (so the orphan sweep does not have to scan all state:* keys). */
    public static final String KEY_ACTIVE_SESSIONS = KEY_PREFIX + "active-sessions";
    public static final String KEY_OWNER_PREFIX = KEY_PREFIX + "owner:";
    public static final String KEY_PENDING_RUN_PREFIX = KEY_PREFIX + "pending-run:";
    /** KernelContext distributed cache (RMapCache, key=sessionId, per-entry TTL). */
    public static final String KEY_CTX = KEY_PREFIX + "ctx";
    public static final String KEY_QUEUE_PREFIX = KEY_PREFIX + "queue:";
    public static final String KEY_QUEUE_LOCK_PREFIX = KEY_PREFIX + "queue-lock:";
    public static final String KEY_QUEUE_CONSUMED_PREFIX = KEY_PREFIX + "queue-consumed:";
    public static final String KEY_STEER_PREFIX = KEY_PREFIX + "steer:";
    public static final String KEY_CANCEL_RUN_PREFIX = KEY_PREFIX + "cancel-run:";
    public static final String KEY_CANCEL_SESSION_PREFIX = KEY_PREFIX + "cancel-session:";
    public static final String KEY_EVENT_CACHE_PREFIX = KEY_PREFIX + "event-cache:";
    public static final String KEY_EVENT_CACHE_USER_PREFIX = KEY_PREFIX + "event-cache-user:";

    /** Atomic placeholder key for AES key initialization (`SET NX`). */
    public static final String KEY_AES_KEY_INIT = "crypto:aes-key:init";

    /**
     * Timeline sequence allocator key prefix (RAtomicLong, per session).
     *
     * <p>Deliberately {@code agent:} rather than {@code runtime:}: it lives as long as the session
     * but is not runtime state, and the historical session cleanup task must delete it explicitly by
     * sessionId — the key has <b>no TTL</b>, so leaving it behind means it stays forever.
     */
    public static final String KEY_TIMELINE_SEQ_PREFIX = "agent:timeline:seq:";

    private DistributedRuntimeConstants() {
    }

    public static String sessionLockKey(Long sessionId) {
        return KEY_LOCK_PREFIX + sessionId;
    }

    public static String sessionStateKey(Long sessionId) {
        return KEY_STATE_PREFIX + sessionId;
    }

    public static String sessionOwnerKey(Long sessionId) {
        return KEY_OWNER_PREFIX + sessionId;
    }

    public static String sessionPendingRunKey(Long sessionId) {
        return KEY_PENDING_RUN_PREFIX + sessionId;
    }

    public static String sessionQueueKey(Long sessionId) {
        return KEY_QUEUE_PREFIX + sessionId;
    }

    public static String sessionQueueLockKey(Long sessionId) {
        return KEY_QUEUE_LOCK_PREFIX + sessionId;
    }

    public static String sessionQueueConsumedKey(Long sessionId) {
        return KEY_QUEUE_CONSUMED_PREFIX + sessionId;
    }

    public static String sessionSteerKey(Long sessionId) {
        return KEY_STEER_PREFIX + sessionId;
    }

    public static String runCancelKey(Long runId) {
        return KEY_CANCEL_RUN_PREFIX + runId;
    }

    public static String sessionCancelKey(Long sessionId) {
        return KEY_CANCEL_SESSION_PREFIX + sessionId;
    }

    public static String sessionEventCacheKey(Long sessionId) {
        return KEY_EVENT_CACHE_PREFIX + sessionId;
    }

    public static String userEventCacheKey(String username) {
        return KEY_EVENT_CACHE_USER_PREFIX + username;
    }

    public static String timelineSeqKey(Long sessionId) {
        return KEY_TIMELINE_SEQ_PREFIX + sessionId;
    }
}
