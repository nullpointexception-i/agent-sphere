package com.buukle.agent.runtime.orchestration.controller;

import com.buukle.agent.runtime.orchestration.service.SseRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/runtime")
@RequiredArgsConstructor
public class SseRuntimeController {

    private final SseRuntimeService sseRuntimeService;

    @GetMapping("/{sessionId}/stream")
    public SseEmitter stream(@PathVariable Long sessionId) {
        return sseRuntimeService.stream(sessionId);
    }
}
