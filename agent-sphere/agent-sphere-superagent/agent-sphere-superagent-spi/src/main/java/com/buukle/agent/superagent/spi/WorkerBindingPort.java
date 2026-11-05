package com.buukle.agent.superagent.spi;

/**
 * Worker 会话绑定：Run 启动前先把 sessionId 写回 Work Item，
 * 这样即使 run 很快结束，终态回报也能按 worker_session_id 找到任务。
 */
public interface WorkerBindingPort {
    void bind(Long workItemId, Long workerSessionId);
}
