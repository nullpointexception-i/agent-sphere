package com.buukle.agent.project.event;

/** Published after a requirement comment mention is persisted. */
public record RequirementCommentMentionedEvent(
        Long tenantId,
        Long projectId,
        Long requirementId,
        Long commentId,
        Long mentionId,
        String mentionType,
        Long targetUserId,
        Long targetAgentId,
        Long actorUserId
) {
}
