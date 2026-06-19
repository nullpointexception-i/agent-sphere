package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.runtime.orchestration.sse.SseManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class SseRuntimeService {

    private final SseManager sseManager;

    public SseEmitter stream(Long sessionId) {
        return sseManager.register(sessionId);
    }
}
