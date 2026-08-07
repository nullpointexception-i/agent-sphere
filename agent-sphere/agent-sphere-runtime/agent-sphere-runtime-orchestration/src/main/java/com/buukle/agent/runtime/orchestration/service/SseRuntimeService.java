package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.sse.SseManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class SseRuntimeService {

    private final SseManager sseManager;
    private final SessionSpi sessionSpi;

    public SseEmitter stream(Long sessionId) {
        assertSessionOwnership(sessionId);
        return sseManager.register(sessionId);
    }

    /** 用户级 task 指令流（浏览器插件永久连接，不依赖 session）。 */
    public SseEmitter streamUser() {
        String username = AuthContext.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return sseManager.registerUser(username);
    }

    private void assertSessionOwnership(Long sessionId) {
        if (AuthContext.isSuperAdmin()) return;
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !AuthContext.getUsername().equals(session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
