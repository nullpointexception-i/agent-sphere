package com.buukle.agent.common.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task-level credential store.
 *
 * <p>These assertions guard two things that have gone wrong before:
 * <ol>
 *   <li><b>Connections must go through Redisson</b>. Previously {@code StringRedisTemplate} was used,
 *       while this project's Redis config sits under the legacy prefix {@code spring.redis.*}
 *       (Spring Boot 3 auto-configuration binds only {@code spring.data.redis.*}), so the connection
 *       silently fell back to {@code localhost:6379} and the credential was never written —
 *       surfacing downstream as "missing X-Task-Mcp-Credential", very far from the real root cause.</li>
 *   <li><b>Failures must not propagate</b>. Redis jitter should not fail a visiting task (MCP is
 *       only an enhancement), but it must leave an ERROR-level log instead of staying silent as
 *       before.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TaskMcpCredentialStoreTest {

    private static final Long SESSION_ID = 42L;
    private static final String KEY = "runtime:mcp:task-cred:42";

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;

    private TaskMcpCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new TaskMcpCredentialStore(redissonClient);
    }

    @Test
    void put_storesCredentialUnderSessionKeyWithTtl() {
        when(redissonClient.<String>getBucket(KEY)).thenReturn(bucket);

        store.put(SESSION_ID, "cred-token");

        verify(bucket).set("cred-token", TaskMcpCredentialStore.DEFAULT_TTL);
    }

    /**
     * The task path passes a TTL of "task timeout + margin"; if that were ignored and fell back to
     * the fixed 2h, long tasks (up to 4h) would hit "the task is still running but the credential in
     * Redis is already gone".
     */
    @Test
    void put_withExplicitTtl_usesThatTtl() {
        when(redissonClient.<String>getBucket(KEY)).thenReturn(bucket);
        java.time.Duration ttl = java.time.Duration.ofMinutes(20);

        store.put(SESSION_ID, "cred-token", ttl);

        verify(bucket).set("cred-token", ttl);
    }

    @Test
    void put_blankCredentialIsNoOp() {
        store.put(SESSION_ID, "   ");
        store.put(SESSION_ID, null);
        store.put(null, "cred-token");

        verifyNoInteractions(redissonClient);
    }

    @Test
    void get_returnsStoredCredential() {
        when(redissonClient.<String>getBucket(KEY)).thenReturn(bucket);
        when(bucket.get()).thenReturn("cred-token");

        assertEquals("cred-token", store.get(SESSION_ID));
    }

    @Test
    void get_returnsNullWhenRedisUnavailable() {
        when(redissonClient.<String>getBucket(anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis server: localhost/127.0.0.1:6379"));

        // when it cannot be read, return null (callers treat that as "no credential"); never let the exception interrupt the task
        assertNull(store.get(SESSION_ID));
    }

    @Test
    void put_failureDoesNotPropagate() {
        when(redissonClient.<String>getBucket(anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis server"));

        assertDoesNotThrow(() -> store.put(SESSION_ID, "cred-token"));
    }

    @Test
    void evict_failureDoesNotPropagate() {
        when(redissonClient.<String>getBucket(anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis server"));

        assertDoesNotThrow(() -> store.evict(SESSION_ID));
    }

    /**
     * Regression guard: only Redisson may be depended on; Spring Data Redis types must not reappear.
     * If someone switches back to {@code StringRedisTemplate}, this fails immediately and points to
     * the class comment above.
     */
    @Test
    void dependsOnRedissonOnly() {
        Constructor<?> constructor = TaskMcpCredentialStore.class.getConstructors()[0];
        assertEquals(RedissonClient.class, constructor.getParameterTypes()[0]);

        boolean anySpringDataRedisField = Arrays.stream(TaskMcpCredentialStore.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .anyMatch(f -> f.getType().getName().startsWith("org.springframework.data.redis"));
        assertTrue(!anySpringDataRedisField,
                "不得使用 Spring Data Redis 类型：本项目的 Redis 连接由 Redisson 统一管理");
    }
}
