package com.buukle.agent.runtime.kernel.runner.turn;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteAttemptException;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.runner.tool.LiveToolResultPolicy;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Executes one model route: projects messages, gates compaction, builds the request, starts the
 * stream and awaits it with cancellation/timeout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteAttemptExecutor {

    private final AgentRuntimeProperties properties;
    private final CompactionService compactionService;
    private final LlmRequestConfigurer llmRequestConfigurer;
    private final ReasoningEffortResolver reasoningEffortResolver;
    private final KernelLlmService kernelLlmService;
    private final TurnEventSink turnEventSink;
    private final RouteSelector routeSelector;
    private final com.buukle.agent.runtime.kernel.runner.support.RunCancellationStore cancellationStore;

    public void executeRoute(int routeIndex, ModelRouteFullVO route, TurnAttemptState s) {
        if (cancellationStore.isRunCancelled(s.runId) || cancellationStore.isSessionCancelled(s.sessionId)) {
            s.cancelled.set(true);
            throw new RuntimeException("Run cancelled");
        }
        // Accumulated independently per route: the fallback must not carry over the previous route's partial state.
        s.errorRef.set(null);
        s.errorRetryableRef.set(true);
        s.errorStatusRef.set(null);
        s.finishReasonRef.set(null);
        s.contentRef.set("");
        s.toolCalls.clear();
        s.acceptingEvents.set(true);

        // Project only the request copy: canonical messages keep the full tool results for compaction/snapshots.
        Map<String, Boolean> compressByTool = new HashMap<>();
        for (var t : s.tools) {
            compressByTool.put(t.getLlmToolName(), t.isCompressEnabled());
        }
        List<ChatMessageDTO> projectedMessages = LiveToolResultPolicy.project(s.messages,
                properties.getRunner().getLiveToolResultMaxChars(),
                properties.getRunner().getLiveToolResultBudgetChars(),
                properties.getRunner().getLiveToolResultKeepRecent(),
                properties.getRunner().getLiveBrowserFullMaxChars(),
                compressByTool);

        CompactionService.CompactionDecision decision = compactionService.evaluate(
                projectedMessages, route, s.toolDefs);
        long maxInputTokens = route.getMaxInputTokens() != null
                ? route.getMaxInputTokens() : RunnerConstants.DEFAULT_MAX_INPUT_TOKENS;
        int nominalChatMax = compactionService.resolveNominalChatMaxOutput(
                s.instanceMaxOutput, route.getMaxOutputTokens());
        int requestMax = compactionService.resolveRequestMaxOutput(
                maxInputTokens, decision == null ? 0 : decision.estimated(), nominalChatMax);

        boolean outputExhausted = requestMax < RunnerConstants.MIN_CHAT_OUTPUT_TOKENS;
        s.outputExhaustedRef.set(outputExhausted);
        if (!s.skipCompaction && (outputExhausted || (decision != null && decision.shouldCompact()))) {
            s.compactionTriggered.set(true);
            s.compactionDecisionRef.set(decision);
            return;
        }
        if (outputExhausted) {
            if (routeIndex < s.activeRoutes.size() - 1) {
                throw new RouteAttemptException(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG, null, true);
            }
            s.errorRef.set(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG);
            s.errorRetryableRef.set(false);
            return;
        }

        String apiKey = routeSelector.resolveApiKey(route);
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                .setModel(route.getModelName())
                .setStream(true)
                .setMessages(projectedMessages);
        if (!s.toolDefs.isEmpty()) {
            request.setTools(s.toolDefs);
        }
        llmRequestConfigurer.applyChat(request, s.instanceConfig);
        ReasoningEffortResolver.Resolved reasoning = reasoningEffortResolver.resolve(
                route, route.getConfig(), requestMax, s.ctx.getReasoningEffort());
        if (reasoning.supported() && reasoning.effectiveLevel() != null) {
            request.setReasoningEffort(reasoning.effectiveLevel());
        }
        if (reasoning.thinkingBudgetTokens() != null) {
            request.setThinkingBudget(reasoning.thinkingBudgetTokens());
        }
        if (requestMax > 0) {
            request.setMaxTokens(requestMax);
        }
        if (request.getParallelToolCalls() == null) {
            request.setParallelToolCalls(true);
        }

        CountDownLatch streamDone = new CountDownLatch(1);
        CompletableFuture<Void> future = kernelLlmService.stream(
                route.getCompany(), route.getBaseUrl(), apiKey, route.getModelName(), request,
                event -> turnEventSink.handle(event, s),
                new LlmInteractionMeta().setRunId(s.runId).setSessionId(s.sessionId)
                        .setSuperAgentId(s.ctx.getSession() == null ? null : s.ctx.getSession().getSuperAgentId())
                        .setSessionType(s.ctx.getSession() == null ? null : s.ctx.getSession().getSessionType())
                        .setThreadId(s.ctx.getSession() == null ? null : s.ctx.getSession().getThreadId())
                        .setInteractionType(LlmInteractionType.CHAT_REPLY)
                        .setTurnNo(s.loopCount)
                        .setAttemptNo(routeIndex)
                        .setRetryAttempt(s.retryMark)
                        .setModelRouteId(route.getId())
                        .setEstimatedPromptTokens((int) compactionService.estimatePromptTokens(
                                projectedMessages, s.toolDefs)));

        future.whenComplete((v, ex) -> streamDone.countDown());
        awaitStream(route, s, future, streamDone);

        if (future.isCompletedExceptionally()) {
            s.acceptingEvents.set(false);
            throw new RouteAttemptException("Route failed: " + futureFailureMessage(future), null, true);
        }
        String error = s.errorRef.get();
        if (error != null) {
            s.acceptingEvents.set(false);
            throw new RouteAttemptException("Route failed: " + error,
                    s.errorStatusRef.get(), s.errorRetryableRef.get());
        }
    }

    private void awaitStream(ModelRouteFullVO route, TurnAttemptState s,
                             CompletableFuture<Void> future, CountDownLatch streamDone) {
        try {
            long turnTimeout = properties.getRunner().getTurnTimeout().getSeconds();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeout);
            while (!streamDone.await(1, TimeUnit.SECONDS)) {
                if (cancellationStore.isRunCancelled(s.runId)
                        || cancellationStore.isSessionCancelled(s.sessionId)) {
                    s.cancelled.set(true);
                    future.cancel(true);
                    throw new RuntimeException("Run cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    future.cancel(true);
                    throw new RuntimeException("Turn timed out for route " + route.getId());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Extracts a persistable failure reason from an abnormally completed future. */
    static String futureFailureMessage(CompletableFuture<?> future) {
        try {
            future.get();
            return "LLM stream failed";
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                return "LLM stream failed";
            }
            String message = cause.getMessage();
            return message != null && !message.isBlank()
                    ? message : cause.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "LLM stream interrupted";
        } catch (Exception e) {
            String message = e.getMessage();
            return message != null && !message.isBlank()
                    ? message : e.getClass().getSimpleName();
        }
    }
}
