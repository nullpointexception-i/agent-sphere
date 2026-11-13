package com.buukle.agent.superagent.spi;

/** Read-only work-item state used by the application run reconciler. */
public record WorkItemStatusSnapshot(
        Long workItemId,
        String status,
        String summary
) {
}
