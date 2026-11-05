package com.buukle.agent.superagent.spi;

/** 由 runtime-orchestration 实现：停止 Worker 会话当前 run。 */
public interface WorkerControlPort {
    boolean cancel(Long workerSessionId);
}
