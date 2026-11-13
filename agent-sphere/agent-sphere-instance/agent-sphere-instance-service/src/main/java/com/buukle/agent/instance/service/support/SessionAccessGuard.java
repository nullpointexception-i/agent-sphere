package com.buukle.agent.instance.service.support;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.security.SessionAccessPolicy;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Controller-facing session authorization.
 *
 * <p>SPI-level lookups stay unrestricted for background tasks; API endpoints call this guard so
 * "visible" (tenant administrators) and "writable" (creator only) are enforced consistently.
 * A project assistant baseline is visible to tenant administrators but read-only from the console.
 */
@Component
@RequiredArgsConstructor
public class SessionAccessGuard {

    private final SessionSpi sessionSpi;

    public SessionVO requireReadable(Long sessionId) {
        if (sessionId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "sessionId is required");
        }
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !SessionAccessPolicy.canRead(
                session.getTenantId(), session.getCreatedBy(), session.getSessionType())) {
            // Do not disclose whether another user's session exists.
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "Session not found");
        }
        session.setReadOnly(!SessionAccessPolicy.canWrite(
                session.getTenantId(), session.getCreatedBy(), session.getSessionType(),
                session.getParentSessionId()));
        return session;
    }

    public void requireWritable(Long sessionId) {
        SessionVO session = requireReadable(sessionId);
        if (Boolean.TRUE.equals(session.getReadOnly())) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "This session is read-only");
        }
    }
}
