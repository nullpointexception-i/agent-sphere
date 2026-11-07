package com.buukle.agent.superagent.spi;

/**
 * Worker session binding: write sessionId back to the Work Item before the Run starts,
 * so that even if the run ends quickly, the terminal report can still find the task by worker_session_id.
 */
public interface WorkerBindingPort {
    void bind(Long workItemId, Long workerSessionId);
}
