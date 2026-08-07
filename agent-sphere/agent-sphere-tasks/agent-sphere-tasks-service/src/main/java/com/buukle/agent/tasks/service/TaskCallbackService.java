package com.buukle.agent.tasks.service;

import com.buukle.agent.tasks.domain.AgentTask;

/**
 * 任务终态回调：向调用方回调任务结果。
 */
public interface TaskCallbackService {
    void notifyTerminal(AgentTask task);
}
