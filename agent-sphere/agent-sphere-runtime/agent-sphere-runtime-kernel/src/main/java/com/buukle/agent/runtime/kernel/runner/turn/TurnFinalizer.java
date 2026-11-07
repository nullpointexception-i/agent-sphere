package com.buukle.agent.runtime.kernel.runner.turn;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.complete.FinishReason;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.runner.turn.LlmRetryPolicy;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Folds an attempt's streaming refs into a {@link TurnResult}.
 *
 * <p>Owns compaction fallback, transient-retry classification and finish_reason mapping, keeping
 * the retry loop itself small.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnFinalizer {

    private final AgentRuntimeProperties properties;
    private final CompactionService compactionService;

    /** @return null when the caller should retry inline */
    public TurnResult finalizeAttempt(Long sessionId, Long runId, KernelContext ctx, int loopCount,
                                      int failureNo, AgentRuntimeProperties.RunnerConfig retryCfg,
                                      int maxAttempts,
                                      AtomicBoolean compactionTriggered,
                                      AtomicReference<CompactionService.CompactionDecision> compactionDecisionRef,
                                      AtomicBoolean outputExhaustedRef,
                                      int runCompactionAttempts, int runCompactionCount,
                                      int lastRunCompactionLoop,
                                      AtomicBoolean cancelled,
                                      AtomicReference<String> contentRef, List<TurnToolCall> toolCalls,
                                      AtomicReference<String> errorRef,
                                      AtomicReference<Integer> errorStatusRef,
                                      AtomicBoolean errorRetryableRef,
                                      AtomicReference<String> finishReasonRef,
                                      AtomicReference<String> reasoningRef,
                                      AtomicReference<String> turnReasoningOut,
                                      List<ChatMessageDTO> messages, List<ToolDefinitionDTO> toolDefs,
                                      int currentRunStart) {
        // * Compaction trigger: on a session-level failure / no history, try run-local compaction of the current run.
        if (compactionTriggered.get()) {
            CompactionOutcome sessionOutcome = compactionService.compact(sessionId, runId, ctx);
            if (sessionOutcome == CompactionOutcome.COMPLETED) {
                return TurnResult.compacted(sessionOutcome);
            }
            CompactionService.CompactionDecision decision = compactionDecisionRef.get();
            int maxPerRun = properties.getRunner().getCompaction().getMaxPerRun();
            int cooldownTurns = properties.getRunner().getCompaction().getCooldownTurns();
            boolean hardCeiling = decision != null
                    && decision.estimated() >= decision.maxInputTokens() * 0.9;
            boolean coolingDown = decision != null && !hardCeiling
                    && loopCount - lastRunCompactionLoop < cooldownTurns;
            if (runCompactionAttempts >= 2) {
                if (outputExhaustedRef.get()) {
                    return TurnResult.error(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG);
                }
                log.warn("Run compaction attempts exhausted for run {}; sending oversized context", runId);
            } else if (runCompactionCount >= maxPerRun) {
                if (outputExhaustedRef.get()) {
                    return TurnResult.error(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG);
                }
                log.warn("Run compaction count reached max-per-run ({}) for run {}; sending oversized context",
                        maxPerRun, runId);
            } else if (coolingDown) {
                if (outputExhaustedRef.get()) {
                    return TurnResult.error(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG);
                }
                log.info("Run compaction cooling down for run {} (lastLoop={}, loop={}, cooldown={}); "
                        + "sending current context", runId, lastRunCompactionLoop, loopCount, cooldownTurns);
            } else {
                log.warn("Session compaction made no progress (outcome={}); attempting run-local compaction "
                        + "for run {} (attempt {}, runCount {})", sessionOutcome, runId,
                        runCompactionAttempts + 1, runCompactionCount);
                CompactionService.RunCompactionResult runResult = compactionService.compactRun(sessionId, runId, ctx,
                        new ArrayList<>(messages), currentRunStart, toolDefs);
                if (runResult != null && runResult.outcome() == CompactionOutcome.COMPLETED) {
                    return TurnResult.runCompacted(runResult.messages(), runResult.currentRunStart());
                }
                if (outputExhaustedRef.get()) {
                    return TurnResult.error(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG);
                }
            }
            return TurnResult.compacted(sessionOutcome != null
                    ? sessionOutcome : CompactionOutcome.NO_PROGRESS);
        }

        if (cancelled.get()) {
            turnReasoningOut.set(reasoningRef.get());
            return TurnResult.cancelled(contentRef.get(), toolCalls);
        }

        String error = errorRef.get();
        // Transient failure with no output yet — graded retry: first steps sleep in place, later steps suspend.
        if (error != null && LlmRetryPolicy.isTransient(errorStatusRef.get(), errorRetryableRef.get())
                && contentRef.get().isEmpty() && toolCalls.isEmpty()
                && failureNo <= maxAttempts
                && !LlmRetryPolicy.overTotalWindow(retryCfg, failureNo)) {
            if (LlmRetryPolicy.inline(retryCfg, failureNo)) {
                return null;
            }
            turnReasoningOut.set(reasoningRef.get());
            return TurnResult.retryLater(error, errorStatusRef.get(),
                    LlmRetryPolicy.hintOf(errorStatusRef.get()), failureNo);
        }
        turnReasoningOut.set(reasoningRef.get());
        String finishReason = finishReasonRef.get();
        if (error != null) {
            return TurnResult.error(error).withFinishReason(finishReason);
        }
        if (!toolCalls.isEmpty()) {
            // Tool loop: even if the body was truncated, the tool arguments are already complete or were never usable.
            return TurnResult.toolCalls(contentRef.get(), toolCalls).withFinishReason(finishReason);
        }
        // Partial output without LLMEvent.Finish must not be treated as completion.
        if ((finishReason == null || finishReason.isBlank()) && !contentRef.get().isEmpty()) {
            return TurnResult.error("LLM stream ended without finish_reason (missing LLMEvent.Finish); "
                    + "refusing to treat partial output as completion");
        }
        if (FinishReason.LENGTH.equals(finishReason)) {
            return TurnResult.error("output truncated by max_tokens (finish_reason=length): "
                    + "raise max_tokens or shorten the requested answer")
                    .withFinishReason(finishReason);
        }
        if (FinishReason.CONTENT_FILTER.equals(finishReason)) {
            return TurnResult.error("output blocked by content filter (finish_reason=content_filter)")
                    .withFinishReason(finishReason);
        }
        return TurnResult.complete(contentRef.get()).withFinishReason(finishReason);
    }
}
