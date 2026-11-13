package com.buukle.agent.project.spi;

/** Input of one Project Assistant run. */
public record ProjectAssistantRunCommand(
        Long tenantId,
        Long projectId,
        Long requesterUserId,
        String requesterUsername,
        /** Optional explicit session (the user's fork); canonical runs resolve their own. */
        Long sessionId,
        Long requirementId,
        String message,
        String runMode,
        boolean noClarification,
        String source,
        String idempotencyKey
) {
}
