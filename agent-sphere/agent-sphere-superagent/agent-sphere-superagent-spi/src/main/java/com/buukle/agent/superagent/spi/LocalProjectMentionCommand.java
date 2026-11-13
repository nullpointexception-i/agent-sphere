package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Ask a local desktop runtime to answer one project requirement-comment mention. */
public record LocalProjectMentionCommand(
        Long tenantId,
        Long agentId,
        Long actorUserId,
        String actorName,
        Long projectId,
        Long requirementId,
        Long commentId,
        Long mentionId,
        Long threadId,
        String threadTitle,
        String question
) implements Serializable {
}
