package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.SessionTodo;
import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.SessionTodoMapper;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j

@Service
@RequiredArgsConstructor
public class SessionTodoServiceImpl implements SessionTodoSpi {

    private final SessionTodoMapper mapper;
    private final SessionSpi sessionSpi;

    @Override
    @Transactional
    public void replaceAll(Long sessionId, Long runId, List<SessionTodoVO> todos) {
        mapper.delete(new LambdaQueryWrapper<SessionTodo>()
                .eq(SessionTodo::getSessionId, sessionId));

        if (todos == null || todos.isEmpty()) return;

        AtomicReference<String> creatorRef = new AtomicReference<>();
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session != null) creatorRef.set(session.getCreatedBy());
        } catch (Exception e) {
            log.warn("Failed to resolve creator from session {}", sessionId, e);
        }
        String creator = creatorRef.get();

        List<SessionTodo> entities = todos.stream().map(t -> {
            SessionTodo e = new SessionTodo();
            e.setSessionId(sessionId);
            e.setRunId(runId);
            e.setContent(t.getContent());
            e.setStatus(t.getStatus());
            e.setPriority(t.getPriority());
            e.setSortOrder(0);
            if (creator != null) e.setCreatedBy(creator);
            return e;
        }).toList();

        for (SessionTodo e : entities) {
            mapper.insert(e);
        }
    }

    @Override
    public List<SessionTodoVO> listBySession(Long sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<SessionTodo>()
                        .eq(SessionTodo::getSessionId, sessionId)
                        .orderByAsc(SessionTodo::getSortOrder)
                        .orderByAsc(SessionTodo::getId))
                .stream().map(e -> {
                    SessionTodoVO vo = new SessionTodoVO();
                    vo.setId(e.getId());
                    vo.setSessionId(e.getSessionId());
                    vo.setRunId(e.getRunId());
                    vo.setContent(e.getContent());
                    vo.setStatus(e.getStatus());
                    vo.setPriority(e.getPriority());
                    vo.setSortOrder(e.getSortOrder());
                    return vo;
                }).toList();
    }
}
