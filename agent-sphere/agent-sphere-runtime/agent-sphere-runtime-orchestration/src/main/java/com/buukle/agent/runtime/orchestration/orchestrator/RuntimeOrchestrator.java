package com.buukle.agent.runtime.orchestration.orchestrator;

import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.SessionRunCoordinator;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.runtime.orchestration.pipeline.RuntimeValidator;
import com.buukle.agent.runtime.orchestration.pipeline.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeOrchestrator {

    private final RuntimeValidator validator;
    private final ContextPreparer preparer;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionRunCoordinator coordinator;
    private final SessionInputManager inputManager;

    @Async("runtimeAsyncExecutor")
    public void asyncHandleUserMessage(RunVO run, Long sessionId, String message, Long overrideRouteId) {
        log.info("Async execution start: runId={}, sessionId={}", run.getId(), sessionId);
        try {
            ValidationResult validated = validator.validate(sessionId, overrideRouteId);
            log.info("Validation passed: sessionId={}, instanceId={}, routeId={}",
                sessionId, validated.getAgentInstance().getId(), validated.getModelRoute().getId());

            KernelContext ctx = preparer.prepare(run, validated, message);
            log.info("Context prepared, starting runner: runId={}", run.getId());

            if (run.getDelivery() != null && "queue".equals(run.getDelivery())) {
                inputManager.queue(sessionId, message, overrideRouteId);
            } else {
                inputManager.steer(sessionId, message, overrideRouteId);
            }

            coordinator.wake(sessionId, ctx, run.getId());
        } catch (Exception e) {
            log.error("Runtime execution failed: sessionId={}, runId={}", sessionId, run.getId(), e);
            eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.FAILED,
                new RuntimeEventDataVO()
                    .setSessionId(sessionId)
                    .setRunId(run.getId())
                    .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())
                    .setErrorMessage(e.getMessage())));
        }
    }
}
