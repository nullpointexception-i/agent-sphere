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

    void updateSummary(Long sessionId, String summary, String summaryUpdatedAt, Long modelRouteId);

    void updateSummaryManually(Long sessionId, String summary);

    void touchSession(Long sessionId);

    /**
     * 校验**当前来源端**（主站 / widget，见 {@code SessionSurfaceHolder}）是否允许对该会话继续对话。
     *
     * <p>实例级开关（{@code allow_console_session} / {@code allow_widget_session}）已关掉对应来源时抛
     * {@code SESSION_SURFACE_DISABLED}。未携带来源（服务端内部调用）一律放行。
     */
    void assertSurfaceAllowed(Long sessionId);
}
