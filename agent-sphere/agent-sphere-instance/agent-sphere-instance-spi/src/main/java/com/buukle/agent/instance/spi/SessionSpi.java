package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;

import java.util.List;

public interface SessionSpi {
    SessionVO createSession(CreateSessionDTO dto);

    SessionVO getSession(Long id);

    List<SessionVO> listSessions(int offset, int limit, String keyword);

    void closeSession(Long id);

    void batchCloseSessions(List<Long> ids);

    SessionVO updateSession(Long id, String title);

    SessionVO updateRunMode(Long id, String runMode);

    /** Latest active training session for this agent and creator, or null. */
    SessionVO findActiveTrainingSession(Long superAgentId, String createdBy);

    void updateSummary(Long sessionId, String summary, String summaryUpdatedAt, Long modelRouteId);

    void updateSummaryManually(Long sessionId, String summary);

    void touchSession(Long sessionId);

    void bindWorkItem(Long sessionId, Long workItemId);

    /**
     * Check whether the **current surface** (console / widget; see {@code SessionSurfaceHolder}) may
     * continue the conversation of this session.
     *
     * <p>When the instance-level switches ({@code allow_console_session} / {@code allow_widget_session})
     * have turned off the corresponding surface, {@code SESSION_SURFACE_DISABLED} is thrown. Requests
     * without a surface (server-internal calls) are always allowed.
     */
    void assertSurfaceAllowed(Long sessionId);
}
