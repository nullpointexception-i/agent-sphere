package com.buukle.agent.superagent.spi;

import com.buukle.agent.superagent.dtvo.enums.WorkerDispatchStatus;

import java.io.Serializable;

/** 启动 Worker Run 的结果。 */
public record DispatchResult(
        boolean accepted,
        String status,
        String message,
        Long workerSessionId,
        Long runId
) implements Serializable {

    public static DispatchResult ok() {
        return new DispatchResult(true, WorkerDispatchStatus.ACCEPTED, null, null, null);
    }

    public static DispatchResult ok(Long workerSessionId, Long runId) {
        return new DispatchResult(true, WorkerDispatchStatus.ACCEPTED, null, workerSessionId, runId);
    }

    public static DispatchResult queued(String message) {
        return new DispatchResult(true, WorkerDispatchStatus.QUEUED, message, null, null);
    }

    public static DispatchResult rejected(String message) {
        return new DispatchResult(false, WorkerDispatchStatus.REJECTED, message, null, null);
    }
}
