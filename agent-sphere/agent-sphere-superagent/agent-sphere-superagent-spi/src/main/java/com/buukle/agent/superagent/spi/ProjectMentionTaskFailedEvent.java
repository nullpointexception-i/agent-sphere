package com.buukle.agent.superagent.spi;

/** Published after a local runtime failed a project mention task. */
public record ProjectMentionTaskFailedEvent(
        Long tenantId,
        Long taskId,
        Long mentionId,
        Long agentId,
        String error
) {
}
