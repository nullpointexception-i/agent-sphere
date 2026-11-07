package com.buukle.agent.common.mcp;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Task-level MCP credential store.
 *
 * <p>Why Redis instead of memory: the backend runs replicas=2, so task submission and tool execution
 * may land on different replicas, and an in-process Map fails randomly (showing up as "dedup works
 * sometimes, not other times" — the hardest kind of intermittent bug to diagnose).
 *
 * <p>Why not store it in the agent_task table: the credential would be returned through the API with
 * TaskVO and would show up in all kinds of query views; putting it in a business table means
 * spreading it across every read path. Redis + TTL aligns its lifetime strictly with the task's
 * runtime.
 *
 * <p><b>Why Redisson instead of StringRedisTemplate</b>: this project's Redis connections are
 * created <b>only</b> by infrastructure's {@code RedisConfig}, whose configuration reads
 * {@code spring.redis.host/port} (the legacy prefix). Spring Data Redis auto-configuration binds
 * only {@code spring.data.redis.*}, so with just the legacy prefix it silently falls back to
 * {@code localhost:6379} — there is no local Redis in the container, so every read/write fails.
 * Redisson uses the <b>same already-validated connection</b> as CacheService / locks / the event bus
 * and no longer depends on Boot's property prefix. For the same reason this class deliberately
 * avoids {@code @Value("${spring.redis...}")}.
 */
@Slf4j
@Component
public class TaskMcpCredentialStore {

    /** Default TTL, used when the caller does not specify one (the task path passes "task timeout + margin", see AgentTaskServiceImpl). */
    public static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private static final String KEY_PREFIX = "runtime:mcp:task-cred:";

    private final RedissonClient redissonClient;

    public TaskMcpCredentialStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** Registers the credential on task submission using {@link #DEFAULT_TTL}. An empty credential means the task needs no MCP credential, so it is skipped. */
    public void put(Long sessionId, String credential) {
        put(sessionId, credential, DEFAULT_TTL);
    }

    /**
     * Registers the credential with an explicit TTL on task submission.
     *
     * <p>On the task path the TTL must be ≥ the validity of the JWT the caller issues for the same
     * task: if Redis expires first, the failure degrades into "missing credential" (instead of
     * "credential expired"), which distorts the conclusion and is extremely hard to diagnose.
     * Callers must leave ample margin.
     */
    public void put(Long sessionId, String credential, Duration ttl) {
        if (sessionId == null || credential == null || credential.isBlank()) {
            return;
        }
        try {
            bucket(sessionId).set(credential, ttl != null ? ttl : DEFAULT_TTL);
        } catch (Exception e) {
            // A failed registration must not block the task (the visit itself can still run to
            // completion), but it **must fail loudly**: if the credential was not written, every
            // later MCP call is rejected for "missing credential", and that downstream error is far
            // from the root cause on this side. This used to log only a warning, which is exactly
            // what kept "the credential pipeline is broken" completely silent in production.
            log.error("[MCP] failed to write the task-scoped credential to Redis; MCP duplicate checks are unavailable for this task sessionId={}", sessionId, e);
        }
    }

    /** Fetches the credential by session during tool execution; returns null when absent, and the caller treats that as "no credential". */
    public String get(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return bucket(sessionId).get();
        } catch (Exception e) {
            log.error("[MCP] failed to read the task-scoped credential from Redis; this MCP call will run without it sessionId={}", sessionId, e);
            return null;
        }
    }

    /** Cleared when the task reaches a terminal state, so the credential does not linger in Redis until the TTL expires. */
    public void evict(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            bucket(sessionId).delete();
        } catch (Exception e) {
            log.warn("[MCP] failed to clear the task-scoped credential (main flow is unaffected; TTL will expire it) sessionId={}", sessionId, e);
        }
    }

    private RBucket<String> bucket(Long sessionId) {
        return redissonClient.getBucket(KEY_PREFIX + sessionId);
    }
}
