package com.buukle.agent.superagent.spi;

/** Implemented by runtime-orchestration: start/queue a Worker Run. */
public interface WorkerDispatchPort {
    DispatchResult dispatch(WorkerDispatchCommand command);
}
