package com.buukle.agent.im.controller;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.im.service.ImStreamService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/im")
@WithTenant
@RequiredArgsConstructor
public class ImStreamController {

    private final ImStreamService streamService;

    /** 用户级 IM SSE：只做“有新消息”的提示，权威数据走 REST 补拉。 */
    @GetMapping("/stream")
    public SseEmitter stream(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return streamService.register(AuthContext.getUserId());
    }
}
