package com.buukle.agent.runtime.kernel.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class SessionInputManager {

    private final ConcurrentHashMap<Long, AtomicReference<InputMessage>> steerSlots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingQueue<InputMessage>> queues = new ConcurrentHashMap<>();

    public void steer(Long sessionId, String text, Long modelRouteId) {
        InputMessage msg = new InputMessage(text, modelRouteId, System.currentTimeMillis());
        steerSlots.computeIfAbsent(sessionId, k -> new AtomicReference<>()).set(msg);
        log.debug("Steer input set for session {}", sessionId);
    }

    public void queue(Long sessionId, String text, Long modelRouteId) {
        InputMessage msg = new InputMessage(text, modelRouteId, System.currentTimeMillis());
        queues.computeIfAbsent(sessionId, k -> new LinkedBlockingQueue<>()).add(msg);
        log.debug("Queued input for session {}", sessionId);
    }

    public InputMessage promoteInput(Long sessionId) {
        AtomicReference<InputMessage> slot = steerSlots.get(sessionId);
        if (slot != null) {
            InputMessage msg = slot.getAndSet(null);
            if (msg != null) return msg;
        }
        BlockingQueue<InputMessage> queue = queues.get(sessionId);
        if (queue != null) {
            InputMessage msg = queue.poll();
            if (msg != null && queue.isEmpty()) {
                queues.remove(sessionId);
            }
            return msg;
        }
        return null;
    }

    public boolean hasPending(Long sessionId) {
        AtomicReference<InputMessage> slot = steerSlots.get(sessionId);
        if (slot != null && slot.get() != null) return true;
        BlockingQueue<InputMessage> queue = queues.get(sessionId);
        return queue != null && !queue.isEmpty();
    }

    public boolean hasQueued(Long sessionId) {
        BlockingQueue<InputMessage> queue = queues.get(sessionId);
        return queue != null && !queue.isEmpty();
    }

    public void clear(Long sessionId) {
        steerSlots.remove(sessionId);
        queues.remove(sessionId);
    }

    public record InputMessage(String text, Long modelRouteId, long timestamp) {}
}
