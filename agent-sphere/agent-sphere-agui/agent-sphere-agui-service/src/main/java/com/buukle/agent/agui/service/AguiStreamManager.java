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

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError(e -> emitters.remove(sessionId));
        emitters.put(sessionId, emitter);
        return emitter;
    }

    public void send(Long sessionId, AguiEventVO event) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.getName()).data(event.getData()));
        } catch (Exception e) {
            log.debug("AG-UI emitter closed for session {}, removing: {}", sessionId, e.getMessage());
            emitters.remove(sessionId);
        }
    }

    public void sendError(Long sessionId, String errorMessage) {
        send(sessionId, new AguiEventVO(AguiEventType.RUN_ERROR.getValue(), errorMessage));
    }

    public void complete(Long sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}