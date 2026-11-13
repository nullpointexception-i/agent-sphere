package com.buukle.agent.project.spi;

/** Project/requester binding of one canonical or fork project-assistant session. */
public record ProjectAssistantSessionInfo(
        Long tenantId,
        Long projectId,
        Long canonicalSessionId,
        Long requesterUserId,
        String sessionRole
) {
    public boolean canonical() {
        return ProjectAssistantSessionRole.CANONICAL.value().equals(sessionRole);
    }
}
