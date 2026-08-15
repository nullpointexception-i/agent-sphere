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
 * session/用户级 SSE 事件缓存（Redis List + 滑动 TTL）。
 *
 * 单写者（origin 副本）写、多读者 flush：origin 在发布 topic 前先 append；各副本在客户端
 * （重连到本副本）注册 emitter 时 flush 回放。避免跨副本重连丢事件，且无重复投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventCache {

    private static final int MAX_CACHE_PER_SESSION = 300;

    private final RedissonClient eventBusRedissonClient;
    private final AgentRuntimeProperties properties;

    /** 新 run 开始时清空该 session 缓存，避免重连后重放旧 run 事件（origin 调用）。 */
    public void clearSessionEvents(Long sessionId) {
        getSessionList(sessionId).delete();
    }

    /** origin 在发布 topic 前追加事件。 */
    public void appendSessionEvent(Long sessionId, Object event) {
        RList<Object> list = getSessionList(sessionId);
        list.add(event);
        trimHead(list);
        list.expire(properties.getSse().getCacheTtl());
    }

    /** 新连接注册后回放缓存（读取即清除，避免重复回放）。 */
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
                // emitter 已断：剩余事件丢弃（下次重连再读，缓存被清属可接受丢失，与现状一致）
                break;
            }
        }
        list.delete();
    }

    /** 用户级缓存（插件 task 指令）——origin 追加。 */
    public void appendUserEvent(String username, Object event) {
        RList<Object> list = getUserList(username);
        list.add(event);
        trimHead(list);
        list.expire(properties.getSse().getCacheTtl());
    }

    /** 用户级缓存回放。 */
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
