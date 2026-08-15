package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 会话输入（steer 槽位 / 排队输入）分布式存储。
 * 队列/槽位落 Redis，副本重启或 run 被接管后剩余输入不丢，可续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionInputManager {

    private final RedissonClient redissonClient;

    public void steer(Long sessionId, String text, Long modelRouteId) {
        steer(sessionId, text, modelRouteId, false);
    }

    public void steer(Long sessionId, String text, Long modelRouteId, boolean isClarificationResume) {
        InputMessage msg = new InputMessage(text, modelRouteId, System.currentTimeMillis(), isClarificationResume);
        steerBucket(sessionId).set(msg);
        log.debug("Steer input set for session {}", sessionId);
    }

    public void queue(Long sessionId, String text, Long modelRouteId) {
        queue(sessionId, text, modelRouteId, false);
    }

    public void queue(Long sessionId, String text, Long modelRouteId, boolean isClarificationResume) {
        InputMessage msg = new InputMessage(text, modelRouteId, System.currentTimeMillis(), isClarificationResume);
        queueList(sessionId).add(msg);
        log.debug("Queued input for session {}", sessionId);
    }

    public InputMessage promoteInput(Long sessionId) {
        InputMessage msg = steerBucket(sessionId).getAndDelete();
        if (msg != null) {
            return msg;
        }
        RList<InputMessage> queue = queueList(sessionId);
        if (!queue.isEmpty()) {
            return queue.remove(0);
        }
        return null;
    }

    public boolean hasPending(Long sessionId) {
        if (steerBucket(sessionId).get() != null) {
            return true;
        }
        return !queueList(sessionId).isEmpty();
    }

    public boolean hasQueued(Long sessionId) {
        return !queueList(sessionId).isEmpty();
    }

    public void clear(Long sessionId) {
        steerBucket(sessionId).delete();
        queueList(sessionId).delete();
    }

    private RBucket<InputMessage> steerBucket(Long sessionId) {
        return redissonClient.getBucket(DistributedRuntimeConstants.sessionSteerKey(sessionId));
    }

    private RList<InputMessage> queueList(Long sessionId) {
        return redissonClient.getList(DistributedRuntimeConstants.sessionQueueKey(sessionId));
    }

    public record InputMessage(String text, Long modelRouteId, long timestamp, boolean isClarificationResume) {
    }
}
