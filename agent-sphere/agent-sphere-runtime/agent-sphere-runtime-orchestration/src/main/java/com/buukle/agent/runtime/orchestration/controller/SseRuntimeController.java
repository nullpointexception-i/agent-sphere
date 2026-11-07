package com.buukle.agent.runtime.orchestration.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.runtime.orchestration.service.SseRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(ApiPrefix.V1 + "/runtime")
@WithTenant
@RequiredArgsConstructor
public class SseRuntimeController {

    /** Device identifier reported by the extension (used for connection registration / device selection). */
    public static final String HEADER_DEVICE_ID = "X-Device-Id";
    public static final String HEADER_DEVICE_NAME = "X-Device-Name";

    private final SseRuntimeService sseRuntimeService;

    @GetMapping("/{sessionId}/stream")
    public SseEmitter stream(@PathVariable Long sessionId) {
        return sseRuntimeService.stream(sessionId);
    }

    /** User-level task command stream (persistent browser extension connection). */
    @GetMapping("/user/task/stream")
    public SseEmitter streamUser(HttpServletRequest request) {
        return sseRuntimeService.streamUser(
                request.getHeader(HEADER_DEVICE_ID),
                decodeHeader(request.getHeader(HEADER_DEVICE_NAME)));
    }

    /** Device names may contain non-ASCII characters; the extension URL-encodes them. Decode here, returning the input unchanged on failure. */
    private static String decodeHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
