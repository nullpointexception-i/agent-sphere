package com.buukle.agent.project.dtvo;

import lombok.Data;

/** Ids returned when the project assistant is opened. */
@Data
public class ProjectAssistantSessionVO {
    /** The current user's fork session used by the chat UI. */
    private Long sessionId;
    /** The project's canonical assistant session (shared context owner). */
    private Long canonicalSessionId;
    private Long threadId;
    private Long instanceId;
    private Long projectId;
}
