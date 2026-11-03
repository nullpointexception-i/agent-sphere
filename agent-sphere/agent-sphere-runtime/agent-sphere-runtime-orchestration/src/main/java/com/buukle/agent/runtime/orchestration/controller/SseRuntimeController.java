package com.buukle.agent.runtime.orchestration.controller;

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
@RequestMapping("/api/v1/runtime")
@WithTenant
@RequiredArgsConstructor
public class SseRuntimeController {

    /** 扩展上报的设备标识（连接登记/设备选择用）。 */
    public static final String HEADER_DEVICE_ID = "X-Device-Id";
    public static final String HEADER_DEVICE_NAME = "X-Device-Name";

    private final SseRuntimeService sseRuntimeService;

    @GetMapping("/{sessionId}/stream")
    public SseEmitter stream(@PathVariable Long sessionId) {
        return sseRuntimeService.stream(sessionId);
    }

    /** 用户级 task 指令流（浏览器插件永久连接）。 */
    @GetMapping("/user/task/stream")
    public SseEmitter streamUser(HttpServletRequest request) {
        return sseRuntimeService.streamUser(
                request.getHeader(HEADER_DEVICE_ID),
                decodeHeader(request.getHeader(HEADER_DEVICE_NAME)));
    }

    /** 设备名可能含中文，扩展侧做了 URL 编码；这里解码，失败时原样返回。 */
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
