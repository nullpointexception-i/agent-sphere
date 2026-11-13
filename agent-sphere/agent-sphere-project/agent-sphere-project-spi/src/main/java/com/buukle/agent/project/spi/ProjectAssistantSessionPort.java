package com.buukle.agent.project.spi;

/** Resolves a PROJECT_ROUTER session to its project and requester. */
public interface ProjectAssistantSessionPort {

    /** Null when the session is not a registered project-assistant session. */
    ProjectAssistantSessionInfo findInfoBySessionId(Long sessionId);
}
