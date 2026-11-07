package com.buukle.agent.superagent.spi;

/** Implemented by superagent-service: receives the Worker terminal state and writes it back to the Work Item. */
public interface WorkerReportPort {
    void onWorkerTerminal(WorkerReport report);
}
