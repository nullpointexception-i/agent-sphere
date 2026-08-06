package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiEventType;
import com.buukle.agent.agui.dtvo.AguiEventVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AguiStreamManager {

    private static final String KEY_SEPARATOR = ":";

    /**
     * 按 {@code sessionId:runId} 隔离的 SSE emitter：同一会话的并发 run 各自持有独立流，
     * 事件只路由到发起该 run 的请求，避免跨流漏发（START/END/ARGS 串流）。
     * sessionId-only 键用于 {@code /connect}（无 runId 的场景）。
     */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long sessionId) {
        return register(sessionId, null);
    }

    public SseEmitter register(Long sessionId, Long runId) {
        SseEmitter emitter = new SseEmitter(0L);
        String key = key(sessionId, runId);
        emitter.onCompletion(() -> emitters.remove(key));
        emitter.onTimeout(() -> emitters.remove(key));
        emitter.onError(e -> emitters.remove(key));
        emitters.put(key, emitter);
        return emitter;
    }

    public void send(Long sessionId, Long runId, AguiEventVO event) {
        SseEmitter emitter = emitters.get(key(sessionId, runId));
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.getName()).data(event.getData()));
        } catch (Exception e) {
            log.debug("AG-UI emitter closed for {}/{}, removing: {}", sessionId, runId, e.getMessage());
            emitters.remove(key(sessionId, runId));
        }
    }

    public void sendError(Long sessionId, Long runId, String errorMessage) {
        send(sessionId, runId, new AguiEventVO(AguiEventType.RUN_ERROR.getValue(), errorMessage));
    }

    public void complete(Long sessionId, Long runId) {
        SseEmitter emitter = emitters.remove(key(sessionId, runId));
        if (emitter != null) {
            emitter.complete();
        }
    }

    private static String key(Long sessionId, Long runId) {
        return runId == null ? String.valueOf(sessionId) : sessionId + KEY_SEPARATOR + runId;
    }
}
