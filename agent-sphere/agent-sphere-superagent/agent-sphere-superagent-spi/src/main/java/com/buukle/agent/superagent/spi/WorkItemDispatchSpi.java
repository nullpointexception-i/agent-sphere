package com.buukle.agent.superagent.spi;

/** Direct work-item dispatch used by scheduled and action application scenes. */
public interface WorkItemDispatchSpi {

    Long dispatch(WorkItemCreateCommand command);

    WorkItemStatusSnapshot status(Long workItemId);

    boolean cancel(Long workItemId);

    /** Requeues a non-terminal work item and increments its attempt counter. */
    boolean retry(Long workItemId);
}
