package com.buukle.agent.superagent.spi;

/** Published after a local runtime completed a project mention task. */
public record ProjectMentionTaskCompletedEvent(
        Long tenantId,
        Long taskId,
        Long mentionId,
        Long agentId,
        String agentName,
        String text
) {
}
