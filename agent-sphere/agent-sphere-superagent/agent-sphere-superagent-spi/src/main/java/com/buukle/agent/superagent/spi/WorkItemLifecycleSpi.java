package com.buukle.agent.superagent.spi;

/** Notifies the owning module when a work item reaches a terminal state. */
public interface WorkItemLifecycleSpi {

    default void onStarted(Long workItemId) {
    }

    void onTerminal(Long workItemId, String workItemStatus, String summary, String errorMessage);
}
