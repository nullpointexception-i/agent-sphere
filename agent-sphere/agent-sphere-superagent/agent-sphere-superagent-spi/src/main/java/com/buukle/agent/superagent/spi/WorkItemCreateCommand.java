package com.buukle.agent.superagent.spi;

/** Creates a QUEUED work item for an application run without going through chat routing. */
public record WorkItemCreateCommand(
        Long tenantId,
        Long ownerUserId,
        Long superAgentId,
        Long threadId,
        String title,
        String goal,
        Integer priority,
        String runMode,
        String requestSource,
        String sourceRef,
        String idempotencyKey,
        String entityType,
        Long entityId,
        Long sceneId
) {
}
