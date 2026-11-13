package com.buukle.agent.project.event;

/** Published after a requirement or project write, used to debounce auto analysis. */
public record ProjectRequirementChangedEvent(
        Long tenantId,
        Long projectId,
        Long requirementId
) {
}
