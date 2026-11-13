package com.buukle.agent.project.service;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.ProjectContextSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.spi.ProjectAssistantSessionInfo;
import com.buukle.agent.project.spi.ProjectAssistantSessionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Injects the shared project context into canonical sessions and user forks. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectContextProvider implements ProjectContextSpi {

    private static final int CONTEXT_MAX_CHARS = 6000;

    private final SessionSpi sessionSpi;
    private final PmProjectMapper projectMapper;
    private final ProjectContextEventService contextEventService;
    private final ProjectAssistantSessionPort sessionPort;

    @Override
    public String buildContext(Long sessionId) {
        SessionVO session = sessionSpi.getSessionShared(sessionId);
        if (session == null || !SessionType.PROJECT_ROUTER.equals(session.getSessionType())) {
            return null;
        }
        ProjectAssistantSessionInfo link = sessionPort.findInfoBySessionId(sessionId);
        if (link == null) {
            return null;
        }
        PmProject project = projectMapper.selectById(link.projectId());
        if (project == null) {
            return null;
        }
        String header = "[Project] " + project.getName() + " (" + project.getProjectKey()
                + "), status=" + project.getStatus();
        String events = contextEventService.buildContext(link.projectId(), CONTEXT_MAX_CHARS);
        return events == null ? header : header + "\n" + events;
    }
}
