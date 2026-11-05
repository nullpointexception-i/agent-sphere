package com.buukle.agent.superagent.spi;

/** 由 superagent-service 实现：接收 Worker 终态并回写 Work Item。 */
public interface WorkerReportPort {
    void onWorkerTerminal(WorkerReport report);
}
