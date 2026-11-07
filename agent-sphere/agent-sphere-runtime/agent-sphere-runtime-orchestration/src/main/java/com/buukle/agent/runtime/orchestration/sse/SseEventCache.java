package com.buukle.agent.runtime.orchestration.sse;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Session/user-level SSE event cache (Redis List + sliding TTL).
 *
 * Single writer (origin replica) writes, multiple readers flush: origin appends before publishing to the topic; each replica,
 * when a client (reconnecting to that replica) registers an emitter, flushes to replay. This avoids losing events on cross-replica reconnects without duplicate delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventCache {

    private static final int MAX_CACHE_PER_SESSION = 300;

    private final RedissonClient eventBusRedissonClient;
    private final AgentRuntimeProperties properties;

    /** Clear the session cache when a new run starts, so reconnects do not replay old run events (called by origin). */
    public void clearSessionEvents(Long sessionId) {
        getSessionList(sessionId).delete();
    }

    /** Origin appends the event before publishing to the topic. */
    public void appendSessionEvent(Long sessionId, Object event) {
        RList<Object> list = getSessionList(sessionId);
        list.add(event);
        trimHead(list);
        list.expire(properties.getSse().getCacheTtl());
    }

    /** Replay the cache after a new connection registers (read-and-clear to avoid duplicate replay). */
    public void flushSessionEvents(Long sessionId, SseEmitter emitter) {
        RList<Object> list = getSessionList(sessionId);
        List<Object> events = list.readAll();
        if (events.isEmpty()) {
            return;
        }
        for (Object event : events) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (Exception e) {
                // emitter disconnected: drop the remaining events (they are read on the next reconnect; losing a cleared cache is acceptable and matches current behavior)
                break;
            }
        }
        list.delete();
    }

    /** User-level cache (extension task commands) — appended by origin. */
    public void appendUserEvent(String username, Object event) {
        RList<Object> list = getUserList(username);
        list.add(event);
        trimHead(list);
        list.expire(properties.getSse().getCacheTtl());
    }

    /** Replay the user-level cache. */
    public void flushUserEvents(String username, SseEmitter emitter) {
        RList<Object> list = getUserList(username);
        List<Object> events = list.readAll();
        if (events.isEmpty()) {
            return;
        }
        for (Object event : events) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (Exception e) {
                break;
            }
        }
        list.delete();
    }

    private RList<Object> getSessionList(Long sessionId) {
        return eventBusRedissonClient.getList(DistributedRuntimeConstants.sessionEventCacheKey(sessionId));
    }

    private RList<Object> getUserList(String username) {
        return eventBusRedissonClient.getList(DistributedRuntimeConstants.userEventCacheKey(username));
    }

    private static void trimHead(RList<Object> list) {
        while (list.size() > MAX_CACHE_PER_SESSION) {
            list.fastRemove(0);
        }
    }
}
