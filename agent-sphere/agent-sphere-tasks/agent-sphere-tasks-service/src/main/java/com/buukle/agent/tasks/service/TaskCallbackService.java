package com.buukle.agent.tasks.service;

import com.buukle.agent.tasks.domain.AgentTask;

/**
 * Task terminal-state callback: reports the task result back to the caller.
 */
public interface TaskCallbackService {
    void notifyTerminal(AgentTask task);
}
