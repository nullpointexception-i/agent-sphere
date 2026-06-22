package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;

import java.util.List;

public interface SessionTodoSpi {
    void replaceAll(Long sessionId, Long runId, List<SessionTodoVO> todos);

    List<SessionTodoVO> listBySession(Long sessionId);
}
