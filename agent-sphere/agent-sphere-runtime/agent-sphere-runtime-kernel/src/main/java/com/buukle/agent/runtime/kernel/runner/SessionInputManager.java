package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.runtime.kernel.port.vo.PreparedAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话输入（steer 槽位 / 排队输入）分布式存储。
 * 队列/槽位落 Redis，副本重启或 run 被接管后剩余输入不丢，可续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionInputManager {

    private static final long CONSUMED_TTL_HOURS = 1;

    public enum WithdrawResult { WITHDRAWN, ALREADY_CONSUMED, NOT_FOUND }

    private final RedissonClient redissonClient;

    public void steer(Long sessionId, String text, Long modelRouteId, boolean isClarificationResume,
                      List<PreparedAttachment> attachments) {
        InputMessage msg = new InputMessage(UUID.randomUUID().toString(), text, modelRouteId,
                System.currentTimeMillis(), isClarificationResume,
                attachments != null ? attachments : Collections.emptyList());
        steerBucket(sessionId).set(msg);
        log.debug("Steer input set for session {}", sessionId);
    }

    /** @return queueId, used by the caller to expose withdraw. */
    public String queue(Long sessionId, String text, Long modelRouteId, boolean isClarificationResume,
                        List<PreparedAttachment> attachments) {
        InputMessage msg = new InputMessage(UUID.randomUUID().toString(), text, modelRouteId,
                System.currentTimeMillis(), isClarificationResume,
                attachments != null ? attachments : Collections.emptyList());
        queueList(sessionId).add(msg);
        log.debug("Queued input for session {} queueId={}", sessionId, msg.queueId());
        return msg.queueId();
    }

    public InputMessage promoteInput(Long sessionId) {
        RLock lock = queueLock(sessionId);
        lock.lock();
        try {
            InputMessage msg = steerBucket(sessionId).getAndDelete();
            if (msg == null) {
                RList<InputMessage> queue = queueList(sessionId);
                if (!queue.isEmpty()) {
                    msg = queue.remove(0);
                }
            }
            if (msg != null && msg.queueId() != null && !msg.queueId().isBlank()) {
                consumedMap(sessionId).put(msg.queueId(), "CONSUMED",
                        CONSUMED_TTL_HOURS, TimeUnit.HOURS);
            }
            return msg;
        } finally {
            lock.unlock();
        }
    }

    public WithdrawResult withdraw(Long sessionId, String queueId) {
        if (queueId == null || queueId.isBlank()) return WithdrawResult.NOT_FOUND;
        RLock lock = queueLock(sessionId);
        lock.lock();
        try {
            RList<InputMessage> queue = queueList(sessionId);
            for (int i = 0; i < queue.size(); i++) {
                InputMessage item = queue.get(i);
                if (item.queueId() != null && queueId.equals(item.queueId())) {
                    queue.remove(i);
                    return WithdrawResult.WITHDRAWN;
                }
            }
            if (consumedMap(sessionId).containsKey(queueId)) {
                return WithdrawResult.ALREADY_CONSUMED;
            }
            return WithdrawResult.NOT_FOUND;
        } finally {
            lock.unlock();
        }
    }

    public List<InputMessage> listPending(Long sessionId) {
        return List.copyOf(queueList(sessionId).readAll());
    }

    public boolean hasPending(Long sessionId) {
        if (steerBucket(sessionId).get() != null) {
            return true;
        }
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

    private RLock queueLock(Long sessionId) {
        return redissonClient.getLock(DistributedRuntimeConstants.sessionQueueLockKey(sessionId));
    }

    private RMapCache<String, String> consumedMap(Long sessionId) {
        return redissonClient.getMapCache(DistributedRuntimeConstants.sessionQueueConsumedKey(sessionId));
    }

    public record InputMessage(String queueId, String text, Long modelRouteId, long timestamp,
                               boolean isClarificationResume,
                               List<PreparedAttachment> attachments) {
        public InputMessage {
            attachments = attachments != null ? attachments : Collections.emptyList();
        }

        public InputMessage(String text, Long modelRouteId, long timestamp,
                            boolean isClarificationResume,
                            List<PreparedAttachment> attachments) {
            this(UUID.randomUUID().toString(), text, modelRouteId, timestamp,
                    isClarificationResume, attachments);
        }
    }
}
