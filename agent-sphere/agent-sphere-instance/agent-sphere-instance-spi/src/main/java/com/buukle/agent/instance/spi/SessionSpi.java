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
}
