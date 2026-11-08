package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserTokenSpi;
import com.buukle.agent.runtime.orchestration.sse.SseManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class SseRuntimeService {

    private final SseManager sseManager;
    private final SessionSpi sessionSpi;
    private final UserTokenSpi userTokenSpi;

    public SseEmitter stream(Long sessionId) {
        assertSessionOwnership(sessionId);
        return sseManager.register(sessionId);
    }

    /** User-level task command stream (persistent browser extension connection, independent of any session). */
    public SseEmitter streamUser(String deviceId, String deviceName, String extensionVersion) {
        String username = AuthContext.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return sseManager.registerUser(username, deviceId, deviceName,
                resolveSource(AuthContext.getToken()), extensionVersion);
    }

    /** Connection source surface (CONSOLE/WIDGET): shown in the device list, looked up by token hash. */
    private String resolveSource(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        try {
            return userTokenSpi.findSourceByHash(TokenHasher.sha256(rawToken));
        } catch (Exception e) {
            return null;
        }
    }

    private void assertSessionOwnership(Long sessionId) {
        if (AuthContext.isSuperAdmin()) return;
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !AuthContext.getUsername().equals(session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
