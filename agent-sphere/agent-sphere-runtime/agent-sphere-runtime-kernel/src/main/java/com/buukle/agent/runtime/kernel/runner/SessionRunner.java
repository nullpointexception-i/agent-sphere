package com.buukle.agent.runtime.kernel.runner;



import com.buukle.agent.instance.dtvo.enums.RunType;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.ToolApprovalDecision;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.constant.ToolSecurityLevel;
import com.buukle.agent.common.context.TaskLoopLimitHolder;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.ToolApprovalState;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.*;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.async.FiberSet;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.RouteAttemptException;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.util.json.JsonUtils;
import com.buukle.agent.util.json.ToolCallReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.buukle.agent.model.dtvo.complete.FinishReason;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.*;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.compaction.CompactionTracker;
import com.buukle.agent.runtime.kernel.runner.loop.TurnOutcomeResult;
import com.buukle.agent.runtime.kernel.runner.loop.LoopIteration;
import com.buukle.agent.runtime.kernel.runner.loop.LoopAction;
import com.buukle.agent.runtime.kernel.runner.loop.LoopLimitResolver;
import com.buukle.agent.runtime.kernel.runner.loop.LoopLimitSource;
import com.buukle.agent.runtime.kernel.runner.loop.CompleteTurnResult;
import com.buukle.agent.runtime.kernel.runner.support.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.tool.SuspenseStatusResolver;
import com.buukle.agent.runtime.kernel.runner.turn.LlmRetryPolicy;
import com.buukle.agent.runtime.kernel.runner.turn.TurnAttemptState;
import com.buukle.agent.runtime.kernel.runner.resume.ResumeState;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.service.CompactionService.RunCompactionResult;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.tool.ToolAccessGuard;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import com.buukle.agent.runtime.kernel.util.ScreenshotObservationSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRunner {

    private static final Duration CONTEXT_TTL = Duration.ofMinutes(30);
    /** Maximum number of attachments (images) injected into a single user message. */
    private static final int MAX_USER_ATTACHMENTS = 4;
    /** Placeholder marker for the Redis cancel RSet (the RSet is used as a boolean flag; contains/add both use this value). */
    private static final Object CANCEL_MARKER = Boolean.TRUE;
    private final AgentRuntimeProperties properties;
    private final RunSpi runSpi;
    private final SessionSpi sessionSpi;
    private final KernelLlmService kernelLlmService;
    private final ApiKeySpi apiKeySpi;
    private final ApplicationEventPublisher eventPublisher;
    private final RouteListBuilder routeListBuilder;
    private final FallbackRouteExecutor fallbackRouteExecutor;
    private final HistoryLoader historyLoader;
    private final CompactionService compactionService;
    private final SessionInputManager inputManager;
    private final RunPromptBuilder runPromptBuilder;
    private final ToolExecutor toolExecutor;
    private final TitleService titleService;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final RedissonClient redissonClient;
    private final ChatAttachmentResolver attachmentResolver;
    private final LlmRequestConfigurer llmRequestConfigurer;
    private final ToolApprovalGrantSpi toolApprovalGrantSpi;
    private final ToolApprovalService toolApprovalService;
    private final ReasoningEffortResolver reasoningEffortResolver;

    /**
     * Retry suspension. Field injection is used instead of a constructor parameter on purpose: SessionRunner's constructor is directly
     * instantiated by several tests, so adding a parameter would require rewriting them all, and this dependency has no wiring-order relationship with the existing ones.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.buukle.agent.instance.spi.PendingWaitSpi pendingWaitSpi;

    /** Infrastructure adapters; lazily built so tests that construct SessionRunner directly still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.support.RunCancellationStore cancellationStore;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.support.RunContextStore contextStore;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.prompt.PromptTailWriter promptTailWriter;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.resume.RunResumeCoordinator resumeCoordinator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.tool.FinishToolHandler finishToolHandler;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.tool.LoopCapHandler loopCapHandler;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.turn.TurnEventSink turnEventSink;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.turn.RouteSelector routeSelector;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.turn.RouteAttemptExecutor routeAttemptExecutor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.turn.TurnFinalizer turnFinalizer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.buukle.agent.runtime.kernel.runner.prompt.FirstTurnMessageAssembler firstTurnMessageAssembler;

    private com.buukle.agent.runtime.kernel.runner.prompt.FirstTurnMessageAssembler firstTurn() {
        if (firstTurnMessageAssembler == null) {
            firstTurnMessageAssembler = new com.buukle.agent.runtime.kernel.runner.prompt.FirstTurnMessageAssembler(
                    titleService, toolCallRecordSpi, runPromptBuilder, historyLoader, attachmentResolver, promptTail());
        }
        return firstTurnMessageAssembler;
    }

    private com.buukle.agent.runtime.kernel.runner.turn.TurnFinalizer turnFinalizer() {
        if (turnFinalizer == null) {
            turnFinalizer = new com.buukle.agent.runtime.kernel.runner.turn.TurnFinalizer(
                    properties, compactionService);
        }
        return turnFinalizer;
    }

    private com.buukle.agent.runtime.kernel.runner.turn.RouteSelector routes() {
        if (routeSelector == null) {
            routeSelector = new com.buukle.agent.runtime.kernel.runner.turn.RouteSelector(
                    routeListBuilder, sessionSpi, apiKeySpi, eventPublisher);
        }
        return routeSelector;
    }

    private com.buukle.agent.runtime.kernel.runner.turn.RouteAttemptExecutor routeAttempts() {
        if (routeAttemptExecutor == null) {
            routeAttemptExecutor = new com.buukle.agent.runtime.kernel.runner.turn.RouteAttemptExecutor(
                    properties, compactionService, llmRequestConfigurer, reasoningEffortResolver,
                    kernelLlmService, turnEvents(), routes(), cancellation());
        }
        return routeAttemptExecutor;
    }

    private com.buukle.agent.runtime.kernel.runner.turn.TurnEventSink turnEvents() {
        if (turnEventSink == null) {
            turnEventSink = new com.buukle.agent.runtime.kernel.runner.turn.TurnEventSink(
                    eventPublisher, toolExecutor);
        }
        return turnEventSink;
    }

    private com.buukle.agent.runtime.kernel.runner.tool.FinishToolHandler finishHandler() {
        if (finishToolHandler == null) {
            finishToolHandler = new com.buukle.agent.runtime.kernel.runner.tool.FinishToolHandler(
                    eventPublisher, runSpi, toolExecutor);
        }
        return finishToolHandler;
    }

    private com.buukle.agent.runtime.kernel.runner.tool.LoopCapHandler loopCap() {
        if (loopCapHandler == null) {
            loopCapHandler = new com.buukle.agent.runtime.kernel.runner.tool.LoopCapHandler(
                    toolCallRecordSpi, runSpi, eventPublisher, promptTail());
        }
        return loopCapHandler;
    }

    private com.buukle.agent.runtime.kernel.runner.resume.RunResumeCoordinator resume() {
        if (resumeCoordinator == null) {
            resumeCoordinator = new com.buukle.agent.runtime.kernel.runner.resume.RunResumeCoordinator(
                    toolApprovalService, toolApprovalGrantSpi, runSpi, toolExecutor, eventPublisher,
                    runPromptBuilder, historyLoader, toolCallRecordSpi, promptTail());
        }
        return resumeCoordinator;
    }

    private com.buukle.agent.runtime.kernel.runner.prompt.PromptTailWriter promptTail() {
        if (promptTailWriter == null) {
            promptTailWriter = new com.buukle.agent.runtime.kernel.runner.prompt.PromptTailWriter(
                    toolCallRecordSpi, runPromptBuilder);
        }
        return promptTailWriter;
    }

    private com.buukle.agent.runtime.kernel.runner.support.RunCancellationStore cancellation() {
        if (cancellationStore == null) {
            cancellationStore = new com.buukle.agent.runtime.kernel.runner.support.RunCancellationStore(redissonClient);
        }
        return cancellationStore;
    }

    private com.buukle.agent.runtime.kernel.runner.support.RunContextStore contexts() {
        if (contextStore == null) {
            contextStore = new com.buukle.agent.runtime.kernel.runner.support.RunContextStore(redissonClient);
        }
        return contextStore;
    }

    /** Browser screenshot observation injection support (shared by main/sub agents, with shared ordering/count); depends on attachmentResolver and is initialized lazily. */
    private volatile ScreenshotObservationSupport screenshotObservationSupport;

    /** Run-level cancel: writes a Redis RSet (callable from any replica); the executing replica's loop reads and consumes it. */
    public void cancelRun(Long runId) {
        cancellation().cancelRun(runId);
    }

    /** Session-level cancel: clears the whole current run while it is parked/suspended. */
    public void cancelSession(Long sessionId) {
        cancellation().cancelSession(sessionId);
    }

    /** Clears the session cancel flag (called when there is no active run / it has been consumed). */
    public void clearSessionCancelled(Long sessionId) {
        cancellation().clearSessionCancelled(sessionId);
    }

    /** Whether this session currently has a running run (the owner lease is set by the coordinator). */
    public boolean hasActiveRun(Long sessionId) {
        return cancellation().hasActiveRun(sessionId);
    }

    /** Whether the run is in a cancelled state (for wake/recovery paths to double-check before resuming). */
    public boolean isRunCancelled(Long runId) {
        return cancellation().isRunCancelled(runId);
    }

    /** Whether the session is in a cancelled state (for the coordinator when deciding on abnormal exit). */
    public boolean isSessionCancelled(Long sessionId) {
        return cancellation().isSessionCancelled(sessionId);
    }

    private void clearRunCancelled(Long runId) {
        cancellation().clearRunCancelled(runId);
    }

    public void registerContext(Long sessionId, KernelContext ctx) {
        contexts().registerContext(sessionId, ctx);
    }

    public void setPendingRun(Long sessionId, Long runId) {
        contexts().setPendingRun(sessionId, runId);
    }

    private KernelContext getContext(Long sessionId) {
        return contexts().getContext(sessionId);
    }
    public void run(Long sessionId) {
        boolean parked = false;
        try {
            parked = runInternal(sessionId);
        } finally {
            // Parking-state exit (WAITING / AWAITING_USER) does **not** clear the input queue/steer: the resume input may have
            // just landed in Redis during the "exit window", and clearing it would make the new loop's promoteInput after resume come up empty → the run spins and gets stuck.
            // Other exits (terminal/cancel/no input) still clear, so the coordinator does not misread "there are still messages" and restart the main loop.
            if (!parked) {
                inputManager.clear(sessionId);
            }
            contexts().evictContext(sessionId);
        }
    }

    /** @return whether this run exited in a "parked state" (WAITING / AWAITING_USER) — used by {@link #run} to decide whether to clear input. */
    private boolean runInternal(Long sessionId) {
        boolean parked = false;
        Long currentRunId = contexts().consumePendingRun(sessionId);
        RunVO currentRun = currentRunId != null ? runSpi.getRun(currentRunId) : null;
        // Start from the run's persisted counters (since V82). Suspension resume (clarification/wait) re-enters this method,
        // and if it still started at 0, maxLoopCount would only constrain a "single call" — the model could suspend repeatedly for unlimited extra life.
        int loopCount = currentRun != null && currentRun.getLoopCount() != null
                ? currentRun.getLoopCount() : 0;
        List<ChatMessageDTO> messages = new ArrayList<>();
        boolean hasToolCalls = false;
        StringBuilder allContent = new StringBuilder();

        // Instance-level override (highest priority): KernelContext already carries agentInstance, travels with the run's executing replica, and is naturally consistent across replicas
        KernelContext topCtx = getContext(sessionId);
        Integer instanceLoopLimit = topCtx != null && topCtx.getAgentInstance() != null
                && topCtx.getAgentInstance().getMaxLoopCount() != null
                && topCtx.getAgentInstance().getMaxLoopCount() > 0
                ? topCtx.getAgentInstance().getMaxLoopCount() : null;

        // Limit pinning (V88): a run has several re-entry points (wait resume / clarification resume / reconciliation rescue), while TaskLoopLimitHolder
        // exists only on the task request thread — re-resolving on resume would shrink the same run's budget midway (2000 → falls back to 128).
        // When already pinned, reuse it; resolve only when null (first entry / existing run), then persist it together with loopCount.
        Integer pinnedLoopLimit = currentRun != null ? currentRun.getLoopLimit() : null;
        LoopLimitResolver.ResolvedLoopLimit loopLimit = LoopLimitResolver.resolveMain(
                pinnedLoopLimit, instanceLoopLimit, TaskLoopLimitHolder.get(),
                properties.getRunner().getMaxLoopCount());
        int maxLoopCount = loopLimit.limit();
        LoopLimitSource loopSource = loopLimit.source();
        log.info("Run {} execution source={}, effective loop limit={}", currentRunId, loopSource, maxLoopCount);
        CompactionTracker compaction = new CompactionTracker(currentRunId != null
                ? (int) compactionService.countCompletedRunCompactions(currentRunId) : 0);
        /** Index in messages of the current run's first in-memory message (used by compaction reassembly to keep the current run without replaying the DB). */
        int currentRunStart = -1;
        // L2: once inside the agentic flow, "no tool call this turn" is no longer a completion signal.
        // toolsEverExecuted starts from the persisted loopCount — loopCount increments only on tool loops,
        // so >0 means this run really did execute tools; this still holds after a suspension resume.
        boolean toolsEverExecuted = loopCount > 0;
        int silentTurns = 0;
        int maxSilentTurns = Math.max(0, properties.getRunner().getMaxSilentTurns());

        ResumeState resume = new ResumeState(loopCount, currentRunStart, hasToolCalls, toolsEverExecuted);
        if (!applyApprovalResume(sessionId, currentRunId, currentRun, topCtx, messages, resume)) {
            return true;
        }
        applyRetryResume(sessionId, currentRunId, currentRun, topCtx, messages, resume);
        applyRecoveryResume(sessionId, currentRunId, currentRun, topCtx, messages, resume);
        loopCount = resume.loopCount;
        currentRunStart = resume.currentRunStart;
        hasToolCalls = resume.hasToolCalls;
        toolsEverExecuted = resume.toolsEverExecuted;

        loop:
        while (loopCount < maxLoopCount) {
            LoopIteration iteration = beginLoopIteration(sessionId, currentRunId, currentRun, loopCount,
                    maxLoopCount, hasToolCalls, messages, currentRunStart, silentTurns);
            if (iteration.stop()) {
                break;
            }
            currentRunId = iteration.currentRunId();
            currentRun = iteration.currentRun();
            KernelContext ctx = iteration.ctx();
            currentRunStart = iteration.currentRunStart();
            hasToolCalls = iteration.hasToolCalls();
            List<RuntimeTool> tools = iteration.tools();
            List<ToolDefinitionDTO> toolDefs = iteration.toolDefs();
            boolean isLastLoop = iteration.isLastLoop();

            AtomicReference<String> turnReasoningRef = new AtomicReference<>("");
            TurnResult turn = runTurn(sessionId, currentRunId, messages, toolDefs, tools, ctx, turnReasoningRef,
                    compaction.skip(), currentRunStart, compaction.runAttempts(),
                    compaction.runCount(), compaction.lastRunLoop(), loopCount,
                    currentRun.getRetryAttempt() == null ? 0 : currentRun.getRetryAttempt());

            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) break;

            TurnOutcomeResult outcomeResult = handleTurnOutcome(sessionId, currentRunId, currentRun, ctx, turn,
                    messages, allContent, toolsEverExecuted, silentTurns, maxSilentTurns, compaction,
                    currentRunStart, loopCount);
            silentTurns = outcomeResult.silentTurns();
            currentRunStart = outcomeResult.currentRunStart();
            if (outcomeResult.action() == LoopAction.BREAK) {
                break;
            }
            if (outcomeResult.action() == LoopAction.CONTINUE_WITH_INPUT) {
                hasToolCalls = false;
                continue;
            }
            if (outcomeResult.action() == LoopAction.CONTINUE) {
                continue;
            }
            if (outcomeResult.action() == LoopAction.CONTINUE_TOOL_LOOP) {
                hasToolCalls = true;
                continue;
            }

            // This turn really sent (tool loop): reset the retry counter for this turn; if this run has already failed up to the limit,
            // keep compaction off so every tool loop does not re-attempt a summarization call doomed to fail.
            compaction.resetForToolLoop();
            ToolTurnPreparation prep = prepareToolTurn(sessionId, currentRunId, currentRun, turn, tools,
                    messages, currentRunStart, turnReasoningRef);
            if (prep.parked()) {
                parked = true;
                break;
            }
            ToolBatchResult batch = runToolBatch(sessionId, currentRunId, currentRun, ctx, turn, tools,
                    messages, allContent, prep.deniedReasons(), currentRunStart, loopCount,
                    isLastLoop, maxLoopCount);
            if (batch.parked()) {
                parked = true;
            }
            if (batch.breakLoop()) {
                break;
            }

            hasToolCalls = true;
            toolsEverExecuted = true;
            silentTurns = 0;
            loopCount++;
        }
        finalizeRun(sessionId, currentRunId, currentRun);
        return parked;
    }

    private record ToolTurnPreparation(boolean parked, Map<String, String> deniedReasons) {
    }

    private record ToolBatchResult(boolean breakLoop, boolean parked) {
    }

    /**
     * Builds the assistant(tool_calls) message and resolves approvals. When any call needs user
     * approval the run is parked and the caller must stop the loop.
     */
    private ToolTurnPreparation prepareToolTurn(Long sessionId, Long currentRunId, RunVO currentRun,
                                                TurnResult turn, List<RuntimeTool> tools,
                                                List<ChatMessageDTO> messages, int currentRunStart,
                                                AtomicReference<String> turnReasoningRef) {
        Map<String, String> deniedReasons = new HashMap<>();
        boolean hasFinishCall = turn.toolCalls().stream()
                .anyMatch(tc -> FinishConstants.TOOL_NAME.equals(tc.name()));

        List<ToolCallDTO> toolCallDTOs = new ArrayList<>();
        for (TurnToolCall tc : turn.toolCalls()) {
            toolCallDTOs.add(ToolCallDTO.builder()
                    .id(tc.id())
                    .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                    .function(FunctionDefinitionDTO.builder()
                            .name(tc.name())
                            .arguments(ToolCallReason.strip(tc.arguments()))
                            .build())
                    .build());
        }

        ChatMessageDTO assistantTurn = new ChatMessageDTO()
                .setRole(LlmRoles.ASSISTANT)
                .setToolCalls(toolCallDTOs);
        // The body (if any) must be kept: otherwise compaction/tail/resume would leave only tool calls.
        if (turn.content() != null && !turn.content().isBlank()) {
            assistantTurn.setContent(turn.content());
        }
        String turnReasoning = turnReasoningRef.get();
        if (turnReasoning != null && !turnReasoning.isBlank()) {
            assistantTurn.setReasoningContent(turnReasoning);
        }
        messages.add(assistantTurn);

        if (!hasFinishCall) {
            List<ToolApprovalService.ApprovalCall> allCalls = new ArrayList<>();
            boolean pendingApproval = false;
            String runMode = currentRun.getRunMode();
            for (TurnToolCall tc : turn.toolCalls()) {
                RuntimeTool rt = tools.stream()
                        .filter(t -> tc.name().equals(t.getLlmToolName()))
                        .findFirst().orElse(null);
                if (rt == null) continue;
                String denied = ToolAccessGuard.denyReason(rt, tc.arguments(), runMode);
                if (denied != null) {
                    deniedReasons.put(tc.id(), denied);
                    allCalls.add(new ToolApprovalService.ApprovalCall(tc, rt));
                    continue;
                }
                if (ToolSecurityLevel.from(rt.getSecurityLevel()).requiresApproval()) {
                    if (toolApprovalGrantSpi.isDenied(sessionId, currentRunId, rt.getToolRef())) {
                        deniedReasons.put(tc.id(), userDeniedJson(tc.name()));
                        allCalls.add(new ToolApprovalService.ApprovalCall(tc, rt));
                    } else if (!toolApprovalGrantSpi.isGranted(sessionId, currentRunId,
                            rt.getToolRef(), rt.getApprovalScope())) {
                        allCalls.add(new ToolApprovalService.ApprovalCall(tc, rt, true));
                        pendingApproval = true;
                    } else {
                        allCalls.add(new ToolApprovalService.ApprovalCall(tc, rt));
                    }
                } else {
                    allCalls.add(new ToolApprovalService.ApprovalCall(tc, rt));
                }
            }
            if (pendingApproval) {
                toolApprovalService.createPending(sessionId, currentRunId, allCalls);
                compactionService.snapshotRunTail(currentRunId, messages, currentRunStart);
                currentRun.setStatus(RunStatus.AWAITING_USER.name());
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.AWAITING_USER,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                return new ToolTurnPreparation(true, deniedReasons);
            }
        }

        for (TurnToolCall tc : turn.toolCalls()) {
            String runPublishId = RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id();
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .toolName(tc.name())
                            .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), tools))
                            .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), tools))
                            .argumentsJson(tc.arguments())
                            .publishId(runPublishId).build()));
        }
        return new ToolTurnPreparation(false, deniedReasons);
    }

    /**
     * Executes the tool batch: finishes short-circuit, fiber submission, cancellation, suspension,
     * result messages and the last-loop cap.
     */
    private ToolBatchResult runToolBatch(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext ctx,
                                         TurnResult turn, List<RuntimeTool> tools,
                                         List<ChatMessageDTO> messages, StringBuilder allContent,
                                         Map<String, String> deniedReasons, int currentRunStart,
                                         int loopCount, boolean isLastLoop, int maxLoopCount) {
        // A delegate tool must let the outer fiber's timeout accommodate the sub-agent's budget.
        Duration batchExecutionTimeout = properties.getTool().getExecutionTimeout();
        boolean hasSubRunTool = turn.toolCalls().stream()
                .anyMatch(tc -> toolExecutor.isSubRunTool(tc.name(), tools));
        if (hasSubRunTool) {
            Duration subRunBudget = properties.getDelegate().getExecutionTimeout();
            if (subRunBudget != null && subRunBudget.compareTo(batchExecutionTimeout) > 0) {
                batchExecutionTimeout = subRunBudget;
            }
        }

        FiberSet fibers = new FiberSet(
                properties.getTool().getMaxParallel(),
                properties.getTool().getSubmitTimeout(),
                batchExecutionTimeout);
        final Long sid = sessionId;
        final Long rid = currentRunId;
        final List<RuntimeTool> tl = tools;

        // Push the SUCCEEDED event as each one completes, without waiting for all tools
        fibers.onEachResult(sr -> {
            TurnToolCall matching = turn.toolCalls().stream()
                    .filter(tc -> tc.id().equals(sr.callId()))
                    .findFirst().orElse(null);
            if (matching == null) return;
            String artifact = sr.error() != null
                    ? "{\"error\":\"" + sr.error() + "\"}"
                    : (sr.result() != null ? sr.result() : "");
            if (deniedReasons.containsKey(matching.id())) {
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sid)
                                .runId(rid)
                                .toolName(matching.name())
                                .displayNameCn(toolExecutor.resolveDisplayName(matching.name(), tl))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(matching.name(), tl))
                                .artifact(artifact)
                                .errorMessage("User denied tool execution")
                                .argumentsJson(matching.arguments())
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + matching.id()).build()));
                return;
            }
            // wait's "result" is only a suspension signal, not completion: keep RUNNING until the wake path.
            ToolCallStatus resultStatus = SuspenseStatusResolver.resolve(matching.name(), artifact)
                    == RunStatus.WAITING
                    ? ToolCallStatus.RUNNING
                    : ToolCallStatus.SUCCEEDED;
            eventPublisher.publishEvent(new RuntimeEventVO(resultStatus,
                    RuntimeEventDataVO.builder()
                            .sessionId(sid)
                            .runId(rid)
                            .toolName(matching.name())
                            .displayNameCn(toolExecutor.resolveDisplayName(matching.name(), tl))
                            .displayNameEn(toolExecutor.resolveDisplayNameEn(matching.name(), tl))
                            .artifact(artifact)
                            .compressEnabled(compressEnabled(matching.name(), tl))
                            .argumentsJson(matching.arguments())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + matching.id()).build()));
        });

        // Check for cancellation before ANY tool submission
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            return new ToolBatchResult(true, false);
        }

        // finish: the model explicitly declares the task complete — handled before submitting any tool.
        if (handleFinishTurn(sessionId, currentRunId, currentRun, turn, tools, allContent)) {
            return new ToolBatchResult(true, false);
        }

        for (int i = 0; i < turn.toolCalls().size(); i++) {
            int fi = i;
            TurnToolCall tc = turn.toolCalls().get(fi);
            if (deniedReasons.containsKey(tc.id())) {
                fibers.submit(tc.id(), () -> deniedReasons.get(tc.id()));
            } else {
                fibers.submit(tc.id(),
                        () -> toolExecutor.execute(tc,
                                SubRunExecutionContext.root(sid, rid, ctx), tl));
            }
        }
        var results = fibers.awaitAll(() -> isRunCancelled(rid) || isSessionCancelled(sid));

        // If cancelled during tool execution, publish FAILED events and exit; terminal state is handled post-loop.
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            for (TurnToolCall tc : turn.toolCalls()) {
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .toolName(tc.name())
                                .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), tools))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), tools))
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id()).build()));
            }
            return new ToolBatchResult(true, false);
        }

        // Suspension decision: clarification (person) and wait (time) break this turn out of the loop.
        RunStatus suspendStatus = null;
        for (TurnToolCall tc : turn.toolCalls()) {
            suspendStatus = SuspenseStatusResolver.resolve(tc.name(), results.getOrDefault(tc.id(), ""));
            if (suspendStatus != null) {
                break;
            }
        }
        if (suspendStatus != null) {
            // Suspension tools are also a tool-loop turn: increment and persist so resume uses a new turn_no.
            loopCount++;
            compactionService.snapshotRunTail(currentRunId, messages, currentRunStart);
            currentRun.setLoopCount(loopCount);
            currentRun.setStatus(suspendStatus.name());
            runSpi.updateRun(currentRun);
            eventPublisher.publishEvent(new RuntimeEventVO(suspendStatus,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
            return new ToolBatchResult(true, true);
        }

        for (TurnToolCall tc : turn.toolCalls()) {
            String toolResult = results.getOrDefault(tc.id(), RunnerConstants.JSON_ERROR_TOOL_LOST);
            String toolMessage = toolResult;
            if (tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId())) {
                // The full list is already delivered through the tail todo slot; keep the receipt short.
                toolMessage = RunnerConstants.TODO_WRITE_ACK;
            }
            messages.add(new ChatMessageDTO()
                    .setRole(LlmRoles.TOOL)
                    .setToolCallId(tc.id())
                    .setContent(toolMessage));
            // The SUCCEEDED event was already published in the onEachResult callback; do not repeat it here.
        }

        // Append all tool result messages first, then inject screenshot observations uniformly.
        for (TurnToolCall tc : turn.toolCalls()) {
            if (tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId())) {
                injectScreenshotObservation(sessionId, currentRunId, messages,
                        results.getOrDefault(tc.id(), RunnerConstants.JSON_ERROR_TOOL_LOST));
            }
        }

        if (handleAfterToolLoop(sessionId, currentRunId, currentRun, turn, messages,
                allContent, isLastLoop, maxLoopCount)) {
            return new ToolBatchResult(true, false);
        }
        return new ToolBatchResult(false, false);
    }

    /** Emits the uniform cancellation / missing-terminal-state finalization after the loop exits. */
    private void finalizeRun(Long sessionId, Long currentRunId, RunVO currentRun) {
        // Handle cancellation after the loop exits (both run-level and session-level cancellations finalize uniformly)
        boolean runCancelled = isRunCancelled(currentRunId);
        if (runCancelled) {
            clearRunCancelled(currentRunId);
        }
        boolean sessionCancelled = isSessionCancelled(sessionId);
        if (sessionCancelled) {
            clearSessionCancelled(sessionId);
        }
        ScreenshotObservationSupport support = screenshotObservationSupport;
        if (support != null) {
            support.clear(currentRunId);
        }
        if ((runCancelled || sessionCancelled) && currentRun != null) {
            // On cancel, close all un-woken waits under this run: the row inserted while suspension and cancel raced must be covered too.
            if (pendingWaitSpi != null) {
                pendingWaitSpi.cancelPendingByRunId(currentRunId);
            }
            currentRun.setStatus(RunStatus.CANCELLED.name());
            runSpi.updateRun(currentRun);
            // Emit CANCELLED (not FAILED): consistent with the DB state, and the frontend takes the run_cancelled branch to show "cancelled".
            // The old implementation emitted FAILED, and RuntimeEventListener then overwrote the DB back to FAILED — a cancellation was recorded as a failure.
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.CANCELLED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .assistantReply(RunnerConstants.CANCEL_MSG)
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
        }
        // Safety net: no break / natural exit is allowed to leave the run in RUNNING.
        // At that point the coordinator sets session state to IDLE and clears the owner, while OrphanRunSweeper only recognizes
        // RUNNING/RUNNING_WITH_PENDING + no owner — it would never scan this combination, so without a fallback the run is stuck forever.
        if (currentRun != null && RunStatus.RUNNING.name().equals(currentRun.getStatus())) {
            log.warn("Run {} exited the loop without a terminal status; marking FAILED", currentRunId);
            currentRun.setStatus(RunStatus.FAILED.name());
            runSpi.updateRun(currentRun);
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .errorMessage(RunnerConstants.RUN_LOOP_NO_TERMINAL_MSG)
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
        }
    }

    /** Uniformly writes the run failure terminal state and emits the event, avoiding repeated data assembly. */
    private boolean applyApprovalResume(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext topCtx,
                                        List<ChatMessageDTO> messages, ResumeState resume) {
        return resume().applyApprovalResume(sessionId, currentRunId, currentRun, topCtx, messages, resume);
    }

    private void applyRetryResume(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext topCtx,
                                  List<ChatMessageDTO> messages, ResumeState resume) {
        resume().applyRetryResume(sessionId, currentRunId, currentRun, topCtx, messages, resume);
    }

    private void applyRecoveryResume(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext topCtx,
                                     List<ChatMessageDTO> messages, ResumeState resume) {
        resume().applyRecoveryResume(sessionId, currentRunId, currentRun, topCtx, messages, resume);
    }
    private boolean handleAfterToolLoop(Long sessionId, Long currentRunId, RunVO currentRun, TurnResult turn,
                                        List<ChatMessageDTO> messages, StringBuilder allContent,
                                        boolean isLastLoop, int maxLoopCount) {
        return loopCap().handle(sessionId, currentRunId, currentRun, turn, messages, allContent,
                isLastLoop, maxLoopCount);
    }
    private boolean handleFinishTurn(Long sessionId, Long currentRunId, RunVO currentRun,
                                     TurnResult turn, List<RuntimeTool> tools, StringBuilder allContent) {
        return finishHandler().handle(sessionId, currentRunId, currentRun, turn, tools, allContent);
    }
    /**
     * Promotes pending input, creates the run when needed, marks it RUNNING, assembles the first
     * turn and computes the tail notice/is-last-loop flags.
     */
    private LoopIteration beginLoopIteration(Long sessionId, Long currentRunId, RunVO currentRun,
                                             int loopCount, int maxLoopCount, boolean hasToolCalls,
                                             List<ChatMessageDTO> messages, int currentRunStart,
                                             int silentTurns) {
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            return LoopIteration.stopped();
        }
        log.info("------******************--------run:{}-loopCount:{}", currentRunId, loopCount);
        SessionInputManager.InputMessage input = null;
        if (hasToolCalls && inputManager.hasPending(sessionId)) {
            input = inputManager.promoteInput(sessionId);
            if (input != null) {
                hasToolCalls = false;
            }
        }
        if (input == null && !hasToolCalls) {
            input = inputManager.promoteInput(sessionId);
        }
        if (input == null && !hasToolCalls) {
            return LoopIteration.stopped();
        }

        KernelContext ctx = getContext(sessionId);
        if (currentRun == null && input != null) {
            CreateRunDTO dto = new CreateRunDTO();
            dto.setSessionId(sessionId);
            dto.setType(RunType.AUTO);
            dto.setUserMessage(input.text());
            dto.setReasoningEffort(input.reasoningEffort());
            currentRun = runSpi.createRun(dto);
            currentRunId = currentRun.getId();
        }
        if (ctx == null) {
            // Normally the coordinator has already registered the context before start/resume; an empty value here means the cache expired
            // or the recovery path did not carry the context. Continuing would make tools=[], and the model could not call finish.
            if (currentRun != null) {
                log.error("KernelContext missing for session {} (run {}); failing run instead of running without tools",
                        sessionId, currentRunId);
                failRun(sessionId, currentRun, RunnerConstants.RUN_CONTEXT_LOST_MSG);
            } else {
                log.error("KernelContext missing for session {} and no run is active; skipping execution", sessionId);
            }
            return LoopIteration.stopped();
        }

        // Conditional state update: an already-cancelled/terminal run must not be dragged back to RUNNING by a late wake.
        // The old implementation called updateRun on the whole row, so cancel and suspension races overwrote each other (production run 2995).
        boolean running = runSpi.markRunning(currentRunId, loopCount, maxLoopCount);
        if (!running) {
            log.info("Run {} is no longer active; stopping resumed loop", currentRunId);
            return LoopIteration.stopped();
        }
        currentRun.setStatus(RunStatus.RUNNING.name());
        // Persisted together with this state update (no extra write): loopCount increments at the end of a normal tool loop and
        // before breaking for a suspension tool (wait/ask_clarification); what is recorded here is the number of the loop about to run,
        // so resuming from this value neither repeats nor skips a loop.
        currentRun.setLoopCount(loopCount);
        // The effective limit is persisted at the same place as loopCount (no extra write): both the budget and the counter are per-run,
        // and if the denominator is not pinned it would be re-resolved on the scheduler thread on resume and shrink.
        currentRun.setLoopLimit(maxLoopCount);

        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.RUNNING,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));

        if (!hasToolCalls) {
            currentRunStart = prepareFirstTurnMessages(sessionId, currentRunId, ctx, input, messages,
                    currentRunStart);
        }

        List<RuntimeTool> tools = ctx.getTools() != null ? ctx.getTools() : List.of();
        List<ToolDefinitionDTO> toolDefs = runPromptBuilder.buildToolDefinitions(tools);

        // Tail notice block: per-loop budget + hard finalization near the limit.
        // isLastLoop is still decided by n-1 — that is when the loop cap takes effect (see the tool-loop finalization below),
        // while hard finalization leads by FINAL_TURN_INSTRUCTION_LEAD loops; the two are deliberately different.
        boolean isLastLoop = loopCount >= maxLoopCount - 1;
        applyLoopBudget(messages, loopCount, maxLoopCount);
        applySilentTurnNudge(messages, silentTurns);

        return new LoopIteration(false, currentRunId, currentRun, ctx, currentRunStart, hasToolCalls,
                tools, toolDefs, isLastLoop);
    }

    private int prepareFirstTurnMessages(Long sessionId, Long currentRunId, KernelContext ctx,
                                         SessionInputManager.InputMessage input,
                                         List<ChatMessageDTO> messages, int currentRunStart) {
        return firstTurn().assemble(sessionId, currentRunId, ctx, input, messages, currentRunStart);
    }
    /** What the loop should do after a turn outcome has been handled. */
    /**
     * Dispatches one turn outcome: appends content, resets the retry counter on real success,
     * reassembles history after compaction, and decides whether the loop continues.
     */
    private TurnOutcomeResult handleTurnOutcome(Long sessionId, Long currentRunId, RunVO currentRun,
                                                KernelContext ctx, TurnResult turn,
                                                List<ChatMessageDTO> messages, StringBuilder allContent,
                                                boolean toolsEverExecuted, int silentTurns, int maxSilentTurns,
                                                CompactionTracker compaction, int currentRunStart, int loopCount) {
        if (turn.content() != null && !turn.content().isBlank()) {
            allContent.append(turn.content());
        }
        if (turn.outcome() == TurnOutcome.COMPLETE || turn.outcome() == TurnOutcome.TOOL_CALLS) {
            currentRun.setRetryAttempt(0);
            currentRun.setWaitKind(null);
            runSpi.resetRetryState(currentRunId);
        }
        switch (turn.outcome()) {
            case COMPACTED -> {
                if (!compaction.onCompacted(turn.compactionOutcome(), currentRunId)) {
                    return new TurnOutcomeResult(LoopAction.CONTINUE_TOOL_LOOP, silentTurns, currentRunStart);
                }
                log.info("Compaction completed, reassembling history (attempt {})", compaction.retries());
                List<ChatMessageDTO> currentRunTail = currentRunStart >= 0 && currentRunStart <= messages.size()
                        ? new ArrayList<>(messages.subList(currentRunStart, messages.size()))
                        : new ArrayList<>();
                messages.clear();
                String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx);
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    messages.add(new ChatMessageDTO().setRole(LlmRoles.SYSTEM).setContent(systemPrompt));
                }
                messages.addAll(historyLoader.load(sessionId, currentRunId));
                currentRunStart = messages.size();
                messages.addAll(currentRunTail);
                refreshTodolistSlot(sessionId, messages);
                return new TurnOutcomeResult(LoopAction.CONTINUE_TOOL_LOOP, silentTurns, currentRunStart);
            }
            case RUN_COMPACTED -> {
                if (turn.rebuiltMessages() == null) {
                    compaction.onRunCompactedWithoutMessages(currentRunId);
                    return new TurnOutcomeResult(LoopAction.CONTINUE_TOOL_LOOP, silentTurns, currentRunStart);
                }
                compaction.onRunCompacted(loopCount, currentRunId, turn.rebuiltMessages().size());
                messages.clear();
                messages.addAll(turn.rebuiltMessages());
                currentRunStart = turn.rebuiltCurrentRunStart() != null
                        ? turn.rebuiltCurrentRunStart() : -1;
                refreshTodolistSlot(sessionId, messages);
                return new TurnOutcomeResult(LoopAction.CONTINUE_TOOL_LOOP, silentTurns, currentRunStart);
            }
            case RETRY_LATER -> {
                handleRetryLaterTurn(sessionId, currentRunId, currentRun, turn);
                return new TurnOutcomeResult(LoopAction.BREAK, silentTurns, currentRunStart);
            }
            case ERROR -> {
                handleErrorTurn(sessionId, currentRunId, currentRun, turn);
                return new TurnOutcomeResult(LoopAction.BREAK, silentTurns, currentRunStart);
            }
            case COMPLETE -> {
                CompleteTurnResult complete = handleCompleteTurn(sessionId, currentRunId, currentRun, turn,
                        allContent, toolsEverExecuted, silentTurns, maxSilentTurns);
                return new TurnOutcomeResult(complete.action(), complete.silentTurns(), currentRunStart);
            }
            case CANCELLED -> {
                return new TurnOutcomeResult(LoopAction.BREAK, silentTurns, currentRunStart);
            }
            default -> {
                return new TurnOutcomeResult(LoopAction.PROCEED, silentTurns, currentRunStart);
            }
        }
    }

    /** Retry suspension after a transient LLM failure. Caller always breaks the loop afterwards. */
    private void handleRetryLaterTurn(Long sessionId, Long currentRunId, RunVO currentRun, TurnResult turn) {
        // Cancel and suspension racing in the same window: an already-cancelled run must not create a wait record
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            log.info("Run {} cancelled before retry suspension; not creating wait", currentRunId);
            return;
        }
        if (pendingWaitSpi == null) {
            // Fallback: when there is no suspension SPI (tests / abnormal wiring), degrade to FAILED so the run is at least not silently dropped
            log.error("PendingWaitSpi unavailable; failing run {} instead of suspending for retry", currentRunId);
            currentRun.setStatus(RunStatus.FAILED.name());
            currentRun.setErrorMessage(turn.errorMessage());
            runSpi.updateRun(currentRun);
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .errorMessage(turn.errorMessage())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
            return;
        }
        // Transient LLM failure: graded backoff + suspension (no execution resources held); the wait scheduler wakes it when due to re-run the same turn.
        // Key: this turn does not consume loopCount (the failed turn did not increment it), so after resume it continues from the original loop.
        int attempt = turn.retryAttempt();
        AgentRuntimeProperties.RunnerConfig retryCfg = properties.getRunner();
        int maxAttempts = LlmRetryPolicy.maxAttempts(retryCfg);
        long delayMs = LlmRetryPolicy.delayMs(retryCfg, attempt);
        LocalDateTime wakeAt = LocalDateTime.now().plusNanos(delayMs * 1_000_000L);
        pendingWaitSpi.createRetry(sessionId, currentRunId, attempt, wakeAt,
                turn.retryHint(), turn.httpStatus(), turn.errorMessage());

        // Creating the wait and cancelling can still interleave: check once more and retract the just-created row if already cancelled
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            pendingWaitSpi.cancelPendingByRunId(currentRunId);
            log.info("Run {} cancelled during retry suspension; wait cancelled", currentRunId);
            return;
        }

        currentRun.setRetryAttempt(attempt);
        currentRun.setWaitKind(WaitConstants.KIND_LLM_RETRY);
        currentRun.setStatus(RunStatus.WAITING.name());
        // Conditional update: an already-cancelled/terminal run must not be written back to WAITING
        if (!runSpi.markWaitingForRetry(currentRunId, attempt)) {
            pendingWaitSpi.cancelPendingByRunId(currentRunId);
            log.info("Run {} is no longer active; retry suspension aborted", currentRunId);
            return;
        }

        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.WAITING,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .waitKind(WaitConstants.KIND_LLM_RETRY)
                        .retryAttempt(attempt)
                        .retryMax(maxAttempts)
                        .nextRetryAt(wakeAt.atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli())
                        .retryHint(turn.retryHint())
                        .retryHttpStatus(turn.httpStatus())
                        .errorMessage(turn.errorMessage())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
        log.warn("LLM transient failure, run {} suspended for retry {}/{} in {}ms (hint={}, status={})",
                currentRunId, attempt, maxAttempts, delayMs, turn.retryHint(), turn.httpStatus());
    }

    /** Terminal LLM error. Cancellation wins over failure. Caller always breaks the loop afterwards. */
    private void handleErrorTurn(Long sessionId, Long currentRunId, RunVO currentRun, TurnResult turn) {
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            return;
        }
        currentRun.setStatus(RunStatus.FAILED.name());
        runSpi.updateRun(currentRun);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .errorMessage(turn.errorMessage())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
    }

    /**
     * Handles a COMPLETE outcome, including the L2 silent-turn guard.
     *
     * <p>Reaching COMPLETE means the model delivered an answer; loopCapped must not be set here
     * (only a true tool-loop truncation is a cap).
     */
    private CompleteTurnResult handleCompleteTurn(Long sessionId, Long currentRunId, RunVO currentRun,
                                                  TurnResult turn, StringBuilder allContent,
                                                  boolean toolsEverExecuted, int silentTurns,
                                                  int maxSilentTurns) {
        // When a cancel arrives during the LLM call, cancel takes precedence over completion
        if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
            return new CompleteTurnResult(LoopAction.BREAK, silentTurns);
        }
        if (inputManager.hasPending(sessionId)) {
            return new CompleteTurnResult(LoopAction.CONTINUE_WITH_INPUT, silentTurns);
        }
        // L2: silence in the agentic flow is not completion. The model must finalize explicitly with finish;
        // exceeding the consecutive-silence limit is a failure — otherwise "gave up after a couple of sentences" would be recorded as success.
        CompletionProtocol completionProtocol = CompletionProtocol.from(
                currentRun != null ? currentRun.getCompletionProtocol() : null);
        boolean implicitText = completionProtocol == CompletionProtocol.IMPLICIT_TEXT
                && turn.content() != null && !turn.content().isBlank()
                && (turn.finishReason() == null || FinishReason.STOP.equals(turn.finishReason()));
        if (toolsEverExecuted && !implicitText) {
            int currentSilentTurn = silentTurns + 1;
            if (currentSilentTurn <= maxSilentTurns) {
                log.warn("Run {} ended a turn without any tool call (silent turn {}/{}, finishReason={}, contentChars={})",
                        currentRunId, currentSilentTurn, maxSilentTurns,
                        turn.finishReason(), turn.content() == null ? 0 : turn.content().length());
                return new CompleteTurnResult(LoopAction.CONTINUE, currentSilentTurn);
            }
            log.warn("Run {} kept replying without tools for {} turn(s); failing it (maxSilentTurns={}, finishReason={}, contentChars={})",
                    currentRunId, currentSilentTurn, maxSilentTurns, turn.finishReason(),
                    turn.content() == null ? 0 : turn.content().length());
            currentRun.setStatus(RunStatus.FAILED.name());
            runSpi.updateRun(currentRun);
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .errorMessage("The model produced no tool call and did not call finish for "
                                    + currentSilentTurn + " consecutive turns")
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
            return new CompleteTurnResult(LoopAction.BREAK, currentSilentTurn);
        }
        if (implicitText) {
            log.warn("Run {} completed by implicit text after tool use (completionProtocol={}, finishReason={}, contentChars={})",
                    currentRunId, completionProtocol, turn.finishReason(), turn.content().length());
        }
        String reply = allContent.toString();
        if (reply != null && !reply.isBlank()) {
            currentRun.setAssistantReply(reply);
        }
        currentRun.setStatus(RunStatus.COMPLETED.name());
        runSpi.updateRun(currentRun);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .assistantReply(currentRun.getAssistantReply())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
        logSkillCompliance(sessionId, currentRunId);
        return new CompleteTurnResult(LoopAction.BREAK, silentTurns);
    }

    private void failRun(Long sessionId, RunVO run, String message) {
        if (run == null) {
            return;
        }
        run.setStatus(RunStatus.FAILED.name());
        run.setErrorMessage(message);
        runSpi.updateRun(run);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(run.getId())
                        .errorMessage(message)
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId()).build()));
    }

    /** Extracts a persistable failure reason from an abnormally completed future (ExecutionException needs its cause unwrapped). */
    private static String futureFailureMessage(CompletableFuture<?> future) {
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

    /** currentRunStart = length of the prefix already added to messages + the relative index inside history; when history has no current run, use the end. */
    static int resolveCurrentRunStart(int base, HistoryLoader.HistoryResult history, int totalAfterHistory) {
        if (history == null || history.currentRunStart() < 0) {
            return totalAfterHistory;
        }
        return base + history.currentRunStart();
    }

    /** Marks the run when forced finalization hits the loop limit (the task guard judges failure by loopCapped, so a "half-finished" result cannot pose as complete). */
    private void refreshTodolistSlot(Long sessionId, List<ChatMessageDTO> messages) {
        promptTail().refreshTodolistSlot(sessionId, messages);
    }

    private void applyTodolistSuffix(List<ChatMessageDTO> messages, String todolistText) {
        promptTail().applyTodolistSuffix(messages, todolistText);
    }

    private void applySilentTurnNudge(List<ChatMessageDTO> messages, int silentTurns) {
        promptTail().applySilentTurnNudge(messages, silentTurns);
    }

    private void applyLoopBudget(List<ChatMessageDTO> messages, int loopCount, int maxLoopCount) {
        promptTail().applyLoopBudget(messages, loopCount, maxLoopCount);
    }

    private int tailNoticeBlockStart(List<ChatMessageDTO> messages) {
        return promptTail().tailNoticeBlockStart(messages);
    }
    /**
     * P5: skill execution consistency (**log only**; does not write remark/stopReason).
     *
     * <p>Criteria (loose, computable, not relying on the model's self-report):
     * <ul>
     *   <li>{@code skillReads=0} → the run never read a skill (strongest signal);</li>
     *   <li>{@code writesBeforeSkillRead>0} → a write action occurred before reading the skill (did not follow the skill).</li>
     * </ul>
     * The fixed marker {@code [SKILL_COMPLIANCE]} is emitted so it can be grepped / aggregated later.
     */
    private void logSkillCompliance(Long sessionId, Long runId) {
        try {
            List<AgentToolCallRecordVO> recs = toolCallRecordSpi.listBySessionId(sessionId, runId);
            if (recs == null || recs.isEmpty()) {
                return;
            }
            String skillTool = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.SKILL_LIBRARY.getId();
            String chromeTool = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();
            int skillReads = 0;
            int chromeWrites = 0;
            int writesBeforeSkillRead = 0;
            boolean firstReadSeen = false;
            for (AgentToolCallRecordVO r : recs) {
                String name = r.getToolName();
                if (skillTool.equals(name)) {
                    skillReads++;
                    firstReadSeen = true;
                } else if (chromeTool.equals(name)) {
                    String args = r.getArgumentsJson() == null ? "" : r.getArgumentsJson();
                    if (isChromeWriteAction(args)) {
                        chromeWrites++;
                        if (!firstReadSeen) {
                            writesBeforeSkillRead++;
                        }
                    }
                }
            }
            log.info("[SKILL_COMPLIANCE] session={} run={} skillReads={} chromeWrites={} writesBeforeSkillRead={}",
                    sessionId, runId, skillReads, chromeWrites, writesBeforeSkillRead);
        } catch (Exception e) {
            log.warn("[SKILL_COMPLIANCE] analysis failed session={} run={}", sessionId, runId, e);
        }
    }

    /** chrome write-action detection (based on the argumentsJson action field; aligned with the chrome tools' ACTION_*). */
    private static boolean isChromeWriteAction(String args) {
        return args.contains("\"action\":\"click\"")
                || args.contains("\"action\":\"type\"")
                || args.contains("\"action\":\"key\"")
                || args.contains("\"action\":\"hover\"")
                || args.contains("\"action\":\"clickAt\"");
    }

    private static String userDeniedJson(String toolName) {
        return ToolDeniedResultVO.of(toolName).toString();
    }

    private static boolean compressEnabled(String toolName, List<RuntimeTool> tools) {
        if (tools == null) return false;
        return tools.stream()
                .filter(t -> toolName != null && toolName.equals(t.getLlmToolName()))
                .findFirst()
                .map(RuntimeTool::isCompressEnabled)
                .orElse(false);
    }

    private TurnResult runTurn(Long sessionId, Long runId, List<ChatMessageDTO> messages,
                               List<ToolDefinitionDTO> toolDefs, List<RuntimeTool> tools,
                               KernelContext ctx, AtomicReference<String> turnReasoning,
                               boolean skipCompaction, int currentRunStart, int runCompactionAttempts,
                               int runCompactionCount, int lastRunCompactionLoop, int loopCount,
                               int retryAttempt) {
        AgentRuntimeProperties.RunnerConfig retryCfg = properties.getRunner();
        int maxAttempts = LlmRetryPolicy.maxAttempts(retryCfg);
        AtomicReference<String> turnReasoningOut = turnReasoning;
        for (int attempt = 0; ; attempt++) {
            // 1-based: failures accumulated across suspensions (the first few steps retry in place, later steps suspend)
            int failureNo = retryAttempt + attempt + 1;
            // The first call is not a retry: retry_attempt marks a step only when it is a real retry (inline/suspended)
            Integer retryMark = (attempt > 0 || retryAttempt > 0) ? failureNo : null;
            if (attempt > 0) {
                long backoffMs = LlmRetryPolicy.delayMs(retryCfg, failureNo - 1);
                log.warn("LLM turn failed transiently, retrying {}/{} in {}ms: session={}",
                        failureNo, maxAttempts, backoffMs, sessionId);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return TurnResult.error("LLM turn retry interrupted");
                }
            }
            // Accumulated independently per loop, so retries do not carry over the previous loop's output/tool calls
            AtomicReference<String> contentRef = new AtomicReference<>("");
            List<TurnToolCall> toolCalls = new CopyOnWriteArrayList<>();
            AtomicReference<String> errorRef = new AtomicReference<>();
            // Whether this attempt's error is worth retrying: 4xx (except 408/429) is a terminal error, and retrying would only amplify one failure into many
            java.util.concurrent.atomic.AtomicBoolean errorRetryableRef =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicReference<Integer> errorStatusRef = new AtomicReference<>();
            AtomicReference<String> reasoningRef = new AtomicReference<>("");
            // This attempt's real finish_reason (stop/length/content_filter/tool_calls), letting the caller distinguish
            // "the model stopped on its own" from "truncated/filtered".
            AtomicReference<String> finishReasonRef = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean compactionTriggered = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean outputExhaustedRef = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<CompactionService.CompactionDecision> compactionDecisionRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            String instanceConfig = ctx != null && ctx.getAgentInstance() != null
                    ? ctx.getAgentInstance().getConfig() : null;
            Integer instanceMaxOutput = llmRequestConfigurer.resolveMaxOutputTokens(instanceConfig);
            try {
                List<ModelRouteFullVO> routes = routes().resolveRoutes(sessionId, ctx);

                // Image attachment request: only routes that can handle attachments are allowed, avoiding falling to a text-only model (400/silently ignored)
                routes = routes().selectAttachmentCapableRoutes(routes, messages, sessionId, runId);

                List<ModelRouteFullVO> activeRoutes = routes;
                TurnAttemptState state = new TurnAttemptState(sessionId, runId, messages, toolDefs, tools,
                        ctx, instanceConfig, instanceMaxOutput, skipCompaction, loopCount, retryMark,
                        activeRoutes, contentRef, toolCalls, errorRef, errorRetryableRef, errorStatusRef,
                        reasoningRef, finishReasonRef, compactionTriggered, outputExhaustedRef,
                        compactionDecisionRef, cancelled);
                fallbackRouteExecutor.execute(activeRoutes, (i, route) -> {
                    routeAttempts().executeRoute(i, route, state);
                    return null;
                });
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("Turn execution failed for session {}", sessionId, e);
                    // When all routes fail, errorRef may still be empty (e.g. All routes exhausted),
                    // so the exception itself must be recorded as the failure reason; otherwise it would be treated as an empty reply and processing continues.
                    String current = errorRef.get();
                    if (current == null || current.isBlank()) {
                        String message = e.getMessage();
                        errorRef.set(message != null && !message.isBlank()
                                ? message : e.getClass().getSimpleName());
                    }
                }
            }

            TurnResult finalized = turnFinalizer().finalizeAttempt(sessionId, runId, ctx, loopCount, failureNo, retryCfg,
                    maxAttempts, compactionTriggered, compactionDecisionRef, outputExhaustedRef,
                    runCompactionAttempts, runCompactionCount, lastRunCompactionLoop,
                    cancelled, contentRef, toolCalls, errorRef, errorStatusRef, errorRetryableRef,
                    finishReasonRef, reasoningRef, turnReasoningOut, messages, toolDefs, currentRunStart);
            if (finalized != null) {
                return finalized;
            }
        }
    }
    /** Receives one streaming event for the active route attempt. */
    private void handleTurnEvent(LLMEvent event, com.buukle.agent.runtime.kernel.runner.turn.TurnAttemptState s) {
        turnEvents().handle(event, s);
    }
    private static String getTurnContent(AtomicReference<String> contentRef) {
        return contentRef.get();
    }

    /**
     * Wait duration before retry number {@code attempt}: {@code base × 2^(attempt-1)}, capped at {@code MAX}.
     */
    static long llmTurnRetryBackoffMs(int attempt) {
        if (attempt <= 0) {
            return 0L;
        }
        long delay = RunnerConstants.LLM_TURN_RETRY_BASE_MS;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(delay * 2, RunnerConstants.LLM_TURN_RETRY_MAX_MS);
        }
        return Math.min(delay, RunnerConstants.LLM_TURN_RETRY_MAX_MS);
    }

    /**
     * Builds the user message: plain text when there are no attachments, otherwise OpenAI-compatible
     * content parts (text + images). Attachments carry only fileKey; the data URL is resolved lazily.
     */
    static ChatMessageDTO buildUserMessage(SessionInputManager.InputMessage input,
                                           ChatAttachmentResolver attachmentResolver) {
        return com.buukle.agent.runtime.kernel.runner.prompt.UserMessageBuilder.build(
                input, attachmentResolver, MAX_USER_ATTACHMENTS);
    }
    static boolean hasAttachmentImage(List<ChatMessageDTO> messages) {
        return ScreenshotObservationSupport.hasAttachmentImage(messages);
    }

    static void stripImageParts(List<ChatMessageDTO> messages) {
        ScreenshotObservationSupport.stripImageParts(messages);
    }

    private void injectScreenshotObservation(Long sessionId, Long runId,
                                             List<ChatMessageDTO> messages, String toolResult) {
        ScreenshotObservationSupport support = screenshotObservationSupport;
        if (support == null) {
            support = new ScreenshotObservationSupport(properties.getRunner().getMaxScreenshotsPerRun(), attachmentResolver);
            screenshotObservationSupport = support;
        }
        support.injectScreenshotObservation(sessionId, runId, messages, toolResult);
    }

    /** Takes the smaller value when both output limits are valid; uses the only valid one when just one is; returns null when neither is. */
    private static Integer minPositive(Integer first, Long second) {
        boolean firstValid = first != null && first > 0;
        Integer secondInt = second != null && second > 0
                ? (int) Math.min(second, Integer.MAX_VALUE) : null;
        boolean secondValid = secondInt != null;
        if (firstValid && secondValid) return Math.min(first, secondInt);
        if (firstValid) return first;
        if (secondValid) return secondInt;
        return null;
    }
}
