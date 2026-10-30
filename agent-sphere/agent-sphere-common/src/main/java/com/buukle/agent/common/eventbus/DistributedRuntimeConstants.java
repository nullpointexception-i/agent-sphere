package com.buukle.agent.common.eventbus;

/**
 * 分布式运行态/事件总线相关的 Redis topic 与键前缀常量（common 层，供 infrastructure 与
 * runtime-kernel 共用）。统一前缀 {@code runtime:}；键通过静态方法生成，避免调用处拼串/魔法值。
 */
public final class DistributedRuntimeConstants {

    /** 统一键前缀。 */
    public static final String KEY_PREFIX = "runtime:";

    // ---- topics（Redis pub/sub）----
    public static final String TOPIC_EVENTS = "runtime.events";
    public static final String TOPIC_AGUI = "runtime.agui";
    public static final String TOPIC_CHROME_COMMAND = "runtime.chrome.command";
    public static final String TOPIC_CHROME_CALLBACK = "runtime.chrome.callback";

    // ---- 键前缀 ----
    public static final String KEY_LOCK_PREFIX = KEY_PREFIX + "lock:";
    public static final String KEY_STATE_PREFIX = KEY_PREFIX + "state:";
    /** 当前有运行态会话的 Redis Set（避免 orphan sweep 全量扫描 state:*）。 */
    public static final String KEY_ACTIVE_SESSIONS = KEY_PREFIX + "active-sessions";
    public static final String KEY_OWNER_PREFIX = KEY_PREFIX + "owner:";
    public static final String KEY_PENDING_RUN_PREFIX = KEY_PREFIX + "pending-run:";
    /** KernelContext 分布式缓存（RMapCache，key=sessionId，条目 TTL）。 */
    public static final String KEY_CTX = KEY_PREFIX + "ctx";
    public static final String KEY_QUEUE_PREFIX = KEY_PREFIX + "queue:";
    public static final String KEY_QUEUE_LOCK_PREFIX = KEY_PREFIX + "queue-lock:";
    public static final String KEY_QUEUE_CONSUMED_PREFIX = KEY_PREFIX + "queue-consumed:";
    public static final String KEY_STEER_PREFIX = KEY_PREFIX + "steer:";
    public static final String KEY_CANCEL_RUN_PREFIX = KEY_PREFIX + "cancel-run:";
    public static final String KEY_CANCEL_SESSION_PREFIX = KEY_PREFIX + "cancel-session:";
    public static final String KEY_EVENT_CACHE_PREFIX = KEY_PREFIX + "event-cache:";
    public static final String KEY_EVENT_CACHE_USER_PREFIX = KEY_PREFIX + "event-cache-user:";

    /** AES 密钥初始化原子占位键（`SET NX`）。 */
    public static final String KEY_AES_KEY_INIT = "crypto:aes-key:init";

    /**
     * Timeline 序号分配器键前缀（RAtomicLong，per session）。
     *
     * <p>刻意用 {@code agent:} 而非 {@code runtime:}：它随 session 生命周期存在但不属于运行态，
     * 历史会话清理任务需要按 sessionId 显式删除它 —— 该键<b>没有 TTL</b>，不删就永久残留。
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
