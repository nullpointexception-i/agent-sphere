package com.buukle.agent.superagent.spi;

/** 由 runtime-orchestration 实现：启动/排队 Worker Run。 */
public interface WorkerDispatchPort {
    DispatchResult dispatch(WorkerDispatchCommand command);
}
