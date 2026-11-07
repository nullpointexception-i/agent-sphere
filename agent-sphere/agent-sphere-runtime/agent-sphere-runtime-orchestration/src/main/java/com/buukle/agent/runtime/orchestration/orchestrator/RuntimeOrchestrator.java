package com.buukle.agent.runtime.orchestration.orchestrator;

import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.SessionRunCoordinator;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.PreparedAttachment;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.runtime.orchestration.pipeline.RuntimeValidator;
import com.buukle.agent.runtime.orchestration.pipeline.ValidationResult;
import com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeOrchestrator {

    private final RuntimeValidator validator;
    private final ContextPreparer preparer;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionRunCoordinator coordinator;
    private final SessionInputManager inputManager;
    private final RunSpi runSpi;
    private final ReasoningEffortResolver reasoningEffortResolver;
    private final LlmRequestConfigurer llmRequestConfigurer;
    private final com.buukle.agent.runtime.kernel.service.CompactionService compactionService;


    @Async("runtimeAsyncExecutor")
    public void asyncHandleUserMessage(RunVO run, Long sessionId, String message, Long overrideRouteId, boolean isClarificationResume) {
        asyncHandleUserMessage(run, sessionId, message, overrideRouteId, isClarificationResume, Collections.emptyList());
    }

    @Async("runtimeAsyncExecutor")
    public void asyncResumeForRetry(RunVO run, Long sessionId) {
        log.info("Async LLM-retry resume: runId={}, sessionId={}, pinnedRoute={}",
                run.getId(), sessionId, run.getModelRouteId());
        try {
            ValidationResult validated = validator.validate(sessionId, run.getModelRouteId());
            snapshotRunRouting(run, validated);
            KernelContext ctx = preparer.prepare(run, validated, "");
            ctx.setRetryResume(true);
            coordinator.wake(sessionId, ctx, run.getId());
        } catch (Exception e) {
            log.error("LLM-retry resume failed: sessionId={}, runId={}", sessionId, run.getId(), e);
            eventPublisher.publishEvent(new RuntimeEventVO(
                    RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(run.getId())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())
                            .errorMessage(e.getMessage()).build()));
        }
    }

    @Async("runtimeAsyncExecutor")
    public void asyncResumeInterruptedRun(RunVO run, Long sessionId) {
        log.info("Async interrupted run recovery: runId={}, sessionId={}, pinnedRoute={}",
                run.getId(), sessionId, run.getModelRouteId());
        try {
            ValidationResult validated = validator.validate(sessionId, run.getModelRouteId());
            snapshotRunRouting(run, validated);
            KernelContext ctx = preparer.prepare(run, validated, "");
            ctx.setRecoveryResume(true);
            coordinator.wake(sessionId, ctx, run.getId());
        } catch (Exception e) {
            log.error("Interrupted run recovery failed: sessionId={}, runId={}", sessionId, run.getId(), e);
            eventPublisher.publishEvent(new RuntimeEventVO(
                    RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(run.getId())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())
                            .errorMessage(e.getMessage()).build()));
        }
    }

    @Async("runtimeAsyncExecutor")
    public void asyncResumeToolApproval(RunVO run, Long sessionId, String approvalId) {
        log.info("Async tool approval resume: runId={}, sessionId={}, approvalId={}",
                run.getId(), sessionId, approvalId);
        try {
            ValidationResult validated = validator.validate(sessionId, run.getModelRouteId());
            snapshotRunRouting(run, validated);
            KernelContext ctx = preparer.prepare(run, validated, "");
            ctx.setApprovalResumeId(approvalId);
            coordinator.wake(sessionId, ctx, run.getId());
        } catch (Exception e) {
            log.error("Tool approval resume failed: sessionId={}, runId={}", sessionId, run.getId(), e);
            eventPublisher.publishEvent(new RuntimeEventVO(
                    RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(run.getId())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())
                            .errorMessage(e.getMessage()).build()));
        }
    }

    @Async("runtimeAsyncExecutor")
    public void asyncHandleUserMessage(RunVO run, Long sessionId, String message, Long overrideRouteId,
                                       boolean isClarificationResume, List<PreparedAttachment> attachments) {
        log.info("Async execution start: runId={}, sessionId={}", run.getId(), sessionId);
        try {
            ValidationResult validated = validator.validate(sessionId, overrideRouteId);
            snapshotRunRouting(run, validated);
            log.info("Validation passed: sessionId={}, instanceId={}, routeId={}",
                    sessionId, validated.getAgentInstance().getId(), validated.getModelRoute().getId());

            KernelContext ctx = preparer.prepare(run, validated, message);
            log.info("Context prepared, starting runner: runId={}", run.getId());

            if (run.getDelivery() != null && "queue".equals(run.getDelivery())) {
                inputManager.queue(sessionId, message, overrideRouteId, isClarificationResume, attachments,
                        null);
            } else {
                inputManager.steer(sessionId, message, overrideRouteId, isClarificationResume, attachments,
                        null);
            }

            coordinator.wake(sessionId, ctx, run.getId());
        } catch (Exception e) {
            log.error("Runtime execution failed: sessionId={}, runId={}", sessionId, run.getId(), e);
            eventPublisher.publishEvent(new RuntimeEventVO(
                    RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(run.getId())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())
                            .errorMessage(e.getMessage()).build()));
        }
    }

    /**
     * Pin the effective completion protocol on first entry only.
     *
     * <p>Resolution order: instance override > model route default > STRICT_FINISH.
     * Once written, resume/fallback paths must keep the snapshot so a mid-run model
     * switch cannot silently relax the completion guardrail.
     */
    private void snapshotRunRouting(RunVO run, ValidationResult validated) {
        if (run == null || validated == null) {
            return;
        }
        boolean changed = false;
        if ((run.getCompletionProtocol() == null || run.getCompletionProtocol().isBlank())
                && validated.getAgentInstance() != null) {
            String instanceOverride = validated.getAgentInstance().getCompletionProtocol();
            String routeDefault = validated.getModelRoute() != null
                    ? validated.getModelRoute().getCompletionProtocol() : null;
            run.setCompletionProtocol(CompletionProtocol.resolve(instanceOverride, routeDefault).name());
            changed = true;
        }
        // Route pin (V116): write once on first entry only. All resume entry points then use this route,
        // so re-picking an instance by weight or an admin config change mid-run cannot switch the model of the same run.
        if (run.getModelRouteId() == null && validated.getModelRoute() != null) {
            run.setModelRouteId(validated.getModelRoute().getId());
            changed = true;
        }
        // Reasoning-effort pin (V118): resolve the default level dynamically from the nominal output cap; a user-selected level takes precedence.
        if (validated.getModelRoute() != null) {
            Integer instanceMaxOutput = llmRequestConfigurer.resolveMaxOutputTokens(
                    validated.getAgentInstance() != null
                            ? validated.getAgentInstance().getConfig() : null);
            int nominal = compactionService.resolveNominalChatMaxOutput(
                    instanceMaxOutput, validated.getModelRoute().getMaxOutputTokens());
            ReasoningEffortResolver.Resolved resolved = reasoningEffortResolver.resolve(
                    validated.getModelRoute(), validated.getModelRoute().getConfig(),
                    nominal, run.getReasoningEffort());
            String effort = resolved.supported() ? resolved.selectedLevel() : null;
            Integer budget = resolved.supported() ? resolved.thinkingBudgetTokens() : null;
            if (!java.util.Objects.equals(effort, run.getReasoningEffort())
                    || !java.util.Objects.equals(budget, run.getReasoningBudgetTokens())) {
                run.setReasoningEffort(effort);
                run.setReasoningBudgetTokens(budget);
                changed = true;
            }
        }
        if (changed) {
            runSpi.updateRun(run);
        }
    }
}
