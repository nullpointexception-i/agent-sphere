package com.buukle.agent.superagent.spi;

/** Implemented by runtime-orchestration: stops the current run of the Worker session. */
public interface WorkerControlPort {
    boolean cancel(Long workerSessionId);
}
