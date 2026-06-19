package com.buukle.agent.runtime.kernel;

import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRunCoordinator {

    private final SessionInputManager inputManager;
    private final SessionRunner sessionRunner;

    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void wake(Long sessionId, KernelContext ctx, Long runId) {
        if (ctx != null) sessionRunner.registerContext(sessionId, ctx);
        if (runId != null) sessionRunner.setPendingRun(sessionId, runId);
        wake(sessionId);
    }

    public void wake(Long sessionId) {
        states.compute(sessionId, (k, current) -> {
            if (current == null || current == State.IDLE) {
                executor.submit(() -> {
                    try {
                        sessionRunner.run(sessionId);
                    } catch (Exception e) {
                        log.error("Session runner failed for session {}", sessionId, e);
                    } finally {
                        states.put(sessionId, State.IDLE);
                        if (inputManager.hasPending(sessionId)) {
                            wake(sessionId);
                        }
                    }
                });
                return State.RUNNING;
            }
            if (current == State.RUNNING && inputManager.hasPending(sessionId)) {
                return State.RUNNING_WITH_PENDING;
            }
            return current;
        });
    }

    enum State { IDLE, RUNNING, RUNNING_WITH_PENDING }
}
