package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.ToolApprovalDecision;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.constant.ToolSecurityLevel;
import com.buukle.agent.common.context.TaskLoopLimitHolder;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
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
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
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

    /** Browser screenshot observation injection support (shared by main/sub agents, with shared ordering/count); depends on attachmentResolver and is initialized lazily. */
    private volatile ScreenshotObservationSupport screenshotObservationSupport;

    /** Run-level cancel: writes a Redis RSet (callable from any replica); the executing replica's loop reads and consumes it. */
    public void cancelRun(Long runId) {
        if (runId != null) {
            runCancelSet(runId).add(CANCEL_MARKER);
        }
    }

    /** Session-level cancel: terminates inside the loop based on whether the session owning the current run was stopped (does not depend on runId). */
    public void cancelSession(Long sessionId) {
        if (sessionId != null) {
            sessionCancelSet(sessionId).add(CANCEL_MARKER);
        }
    }

    /** Clears the session cancel flag (called when there is no active run / it has been consumed, to avoid killing the next new run by mistake). */
    public void clearSessionCancelled(Long sessionId) {
        if (sessionId != null) {
            sessionCancelSet(sessionId).delete();
        }
    }

    /** Whether this session currently has a running run (the owner lease is set by the coordinator on RUNNING and cleared on IDLE). */
    public boolean hasActiveRun(Long sessionId) {
        return sessionId != null && redissonClient.getBucket(
                DistributedRuntimeConstants.sessionOwnerKey(sessionId)).isExists();
    }

    private RSet<Object> runCancelSet(Long runId) {
        return redissonClient.getSet(DistributedRuntimeConstants.runCancelKey(runId));
    }

    private RSet<Object> sessionCancelSet(Long sessionId) {
        return redissonClient.getSet(DistributedRuntimeConstants.sessionCancelKey(sessionId));
    }

    /** Whether the run is in a cancelled state (for wake/recovery paths to double-check before resuming). */
    public boolean isRunCancelled(Long runId) {
        return runId != null && runCancelSet(runId).contains(CANCEL_MARKER);
    }

    /** Whether the session is in a cancelled state (for the coordinator when deciding on abnormal exit / restart). */
    public boolean isSessionCancelled(Long sessionId) {
        return sessionId != null && sessionCancelSet(sessionId).contains(CANCEL_MARKER);
    }

    private void clearRunCancelled(Long runId) {
        if (runId != null) {
            runCancelSet(runId).delete();
        }
    }

    public void registerContext(Long sessionId, KernelContext ctx) {
        ctxCache().put(sessionId, ctx, CONTEXT_TTL.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void setPendingRun(Long sessionId, Long runId) {
        if (runId != null) {
            redissonClient.<Long>getBucket(
                    DistributedRuntimeConstants.sessionPendingRunKey(sessionId)).set(runId);
        }
    }

    private KernelContext getContext(Long sessionId) {
        KernelContext ctx = ctxCache().get(sessionId);
        if (ctx != null) {
            // Long runs can last hours: renew every loop so that when the 30-minute TTL expires, tools do not become empty and
            // the model cannot call any tool (including finish), eventually being misjudged by the silent guard as "consecutive silence".
            try {
                ctxCache().put(sessionId, ctx, CONTEXT_TTL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Failed to refresh KernelContext TTL for session {}: {}", sessionId, e.getMessage());
            }
        }
        return ctx;
    }

    private RMapCache<Long, KernelContext> ctxCache() {
        return redissonClient.getMapCache(DistributedRuntimeConstants.KEY_CTX);
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
            ctxCache().remove(sessionId);
        }
    }

    /** @return whether this run exited in a "parked state" (WAITING / AWAITING_USER) — used by {@link #run} to decide whether to clear input. */
    private boolean runInternal(Long sessionId) {
        boolean parked = false;
        Long currentRunId = redissonClient.<Long>getBucket(
                DistributedRuntimeConstants.sessionPendingRunKey(sessionId)).getAndDelete();
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
        int compactionRetries = 0;
        int consecutiveCompactionFailures = 0;
        // Set after compaction retries are exhausted: do not compact again this turn; send the over-context request to the model directly.
        // Otherwise "compact every loop → still over budget → compact again" would only burn through the retry limit, and the old break left the run stuck in RUNNING forever.
        boolean skipCompaction = false;
        // After the consecutive failure count reaches the limit within one run, shut compaction off entirely so every tool loop does not burn another summarization call.
        boolean compactionDisabledForRun = false;
        /** Index in messages of the current run's first in-memory message (used by compaction reassembly to keep the current run without replaying the DB). */
        int currentRunStart = -1;
        /** Number of run-local compactions for the current run (capped at 2, not reset per tool loop). */
        int runCompactionAttempts = 0;
        int runCompactionCount = currentRunId != null
                ? (int) compactionService.countCompletedRunCompactions(currentRunId) : 0;
        int lastRunCompactionLoop = -1000;
        // L2: once inside the agentic flow, "no tool call this turn" is no longer a completion signal.
        // toolsEverExecuted starts from the persisted loopCount — loopCount increments only on tool loops,
        // so >0 means this run really did execute tools; this still holds after a suspension resume.
        boolean toolsEverExecuted = loopCount > 0;
        int silentTurns = 0;
        int maxSilentTurns = Math.max(0, properties.getRunner().getMaxSilentTurns());

        String approvalResumeId = topCtx != null ? topCtx.getApprovalResumeId() : null;
        if (approvalResumeId != null && currentRun != null) {
            ToolApprovalVO approval = toolApprovalService.getByApprovalId(approvalResumeId);
            if (approval == null || !"APPROVED".equals(approval.getStatus())) {
                currentRun.setStatus(RunStatus.FAILED.name());
                currentRun.setErrorMessage("Tool approval is no longer resumable: " + approvalResumeId);
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .errorMessage(currentRun.getErrorMessage())
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                return true;
            }
            String systemPrompt = runPromptBuilder.buildSystemPrompt(topCtx);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
            }
            int historyBase = messages.size();
            HistoryLoader.HistoryResult history = historyLoader.loadResult(sessionId, currentRunId, true);
            if (history != null) {
                messages.addAll(history.messages());
            }
            currentRunStart = resolveCurrentRunStart(historyBase, history, messages.size());

            List<RuntimeTool> resumeTools = topCtx.getTools() != null ? topCtx.getTools() : List.of();
            List<ToolApprovalService.ApprovalCall> approvalCalls =
                    toolApprovalService.calls(approval, resumeTools);
            Set<String> resumeCallIds = new HashSet<>();
            for (ToolApprovalService.ApprovalCall call : approvalCalls) {
                resumeCallIds.add(call.call().id());
            }
            // While an approval is suspending, the tool record is still PENDING; HistoryLoader may add a failed placeholder tool message.
            // On resume those placeholders must be removed first, so the same callId does not end up with two tool messages.
            messages.removeIf(m -> LlmApiConstant.ROLE_TOOL.equals(m.getRole())
                    && resumeCallIds.contains(m.getToolCallId()));
            Map<String, ToolApprovalDecision> decisions = toolApprovalService.decisions(approval);
            Map<String, String> denied = new HashMap<>();
            for (ToolApprovalService.ApprovalCall call : approvalCalls) {
                String callId = call.call().id();
                String reason = ToolAccessGuard.denyReason(
                        call.tool(), call.call().arguments(), currentRun.getRunMode());
                if (reason == null && call.pending()
                        && decisions.getOrDefault(callId, ToolApprovalDecision.DENY)
                        == ToolApprovalDecision.DENY) {
                    reason = userDeniedJson(call.call().name());
                }
                if (reason == null
                        && ToolSecurityLevel.from(call.tool().getSecurityLevel()).requiresApproval()
                        && toolApprovalGrantSpi.isDenied(sessionId, currentRunId, call.tool().getToolRef())) {
                    reason = userDeniedJson(call.call().name());
                }
                if (reason == null && !call.pending()
                        && ToolSecurityLevel.from(call.tool().getSecurityLevel()).requiresApproval()
                        && !toolApprovalGrantSpi.isGranted(sessionId, currentRunId,
                        call.tool().getToolRef(), call.tool().getApprovalScope())) {
                    reason = userDeniedJson(call.call().name());
                }
                if (reason != null) {
                    denied.put(callId, reason);
                }
            }

            for (ToolApprovalService.ApprovalCall call : approvalCalls) {
                String callId = call.call().id();
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .toolName(call.call().name())
                                .displayNameCn(toolExecutor.resolveDisplayName(call.call().name(), resumeTools))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(call.call().name(), resumeTools))
                                .argumentsJson(call.call().arguments())
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + callId).build()));
                String result;
                if (denied.containsKey(callId)) {
                    result = denied.get(callId);
                    eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(currentRunId)
                                    .toolName(call.call().name())
                                    .displayNameCn(toolExecutor.resolveDisplayName(call.call().name(), resumeTools))
                                    .displayNameEn(toolExecutor.resolveDisplayNameEn(call.call().name(), resumeTools))
                                    .artifact(result)
                                    .errorMessage("User denied tool execution")
                                    .argumentsJson(call.call().arguments())
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + callId).build()));
                } else {
                    try {
                        result = toolExecutor.execute(call.call(),
                                SubRunExecutionContext.root(sessionId, currentRunId, topCtx), resumeTools);
                    } catch (Exception e) {
                        result = "{\"error\":\"" + e.getMessage() + "\"}";
                    }
                    eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(currentRunId)
                                    .toolName(call.call().name())
                                    .displayNameCn(toolExecutor.resolveDisplayName(call.call().name(), resumeTools))
                                    .displayNameEn(toolExecutor.resolveDisplayNameEn(call.call().name(), resumeTools))
                                    .artifact(result)
                                    .compressEnabled(compressEnabled(call.call().name(), resumeTools))
                                    .argumentsJson(call.call().arguments())
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + callId).build()));
                }
                String toolMessage = result;
                if (call.call().name().equals(
                        InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId())) {
                    toolMessage = RunnerConstants.TODO_WRITE_ACK;
                }
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_TOOL)
                        .setToolCallId(callId)
                        .setContent(toolMessage));
            }
            toolApprovalService.consume(approvalResumeId);
            topCtx.setApprovalResumeId(null);
            refreshTodolistSlot(sessionId, messages);
            hasToolCalls = true;
            toolsEverExecuted = true;
            loopCount++;
        }

        if (topCtx != null && topCtx.isRetryResume() && currentRun != null) {
            // Retry re-entry after a transient LLM failure: rebuild history only; do **not** add a resume hint and
            // do **not** call failRunningByRunId (the previous failure produced no tool records).
            String systemPrompt = runPromptBuilder.buildSystemPrompt(topCtx);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
            }
            int historyBase = messages.size();
            HistoryLoader.HistoryResult history = historyLoader.loadResult(sessionId, currentRunId, true);
            if (history != null) {
                messages.addAll(history.messages());
            }
            currentRunStart = resolveCurrentRunStart(historyBase, history, messages.size());
            refreshTodolistSlot(sessionId, messages);
            hasToolCalls = true;
            // Note: toolsEverExecuted must not be set to true here. It should be driven by the persisted loopCount:
            // if a pure chat run (loopCount=0) is force-marked as "used tools" after a retry resume, a normal text answer would be
            // treated by the L2 silent guard as "silence after tools", and continue would send another request (production runs 2999/2995:
            // 300ms after a successful reply a new request was issued and retries continued).
            topCtx.setRetryResume(false);
            log.info("Resumed run {} after transient LLM failure with {} history message(s)",
                    currentRunId, messages.size());
        }

        if (topCtx != null && topCtx.isRecoveryResume() && currentRun != null) {
            toolCallRecordSpi.failRunningByRunId(currentRunId, "interrupted by server restart");
            String systemPrompt = runPromptBuilder.buildSystemPrompt(topCtx);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
            }
            int historyBase = messages.size();
            HistoryLoader.HistoryResult history = historyLoader.loadResult(sessionId, currentRunId, true);
            if (history != null) {
                messages.addAll(history.messages());
            }
            currentRunStart = resolveCurrentRunStart(historyBase, history, messages.size());
            // History reconstruction replays only run.getUserMessage(); synthetic user messages such as the wait-resume hint are not persisted.
            // Without the tail user anchor: (1) the model does not know it was just interrupted by a restart and tends to repeat already-completed tool calls when it wakes;
            // (2) a long stretch of assistant/tool messages follows the last user, and the reconstructed content-only assistant
            // gets pushed into DeepSeek's reasoning_content validation window (production run 2794's 400).
            // Adding a resume hint aligns the structure with the wait-resume path: the tail always has one user message.
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_USER)
                    .setContent(RunnerConstants.RUN_RECOVERY_RESUME_PROMPT));
            // The recovery path does not go through the first-turn branch (hasToolCalls=true), so the todo slot must be restored here, otherwise the plan is lost after reconstruction.
            refreshTodolistSlot(sessionId, messages);
            hasToolCalls = true;
            toolsEverExecuted = true;
            topCtx.setRecoveryResume(false);
            log.info("Recovered interrupted run {} with {} history message(s)", currentRunId, messages.size());
        }

        loop:
        while (loopCount < maxLoopCount) {
            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) break;
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
            if (input == null && !hasToolCalls) break;

            KernelContext ctx = getContext(sessionId);
            if (currentRun == null && input != null) {
                CreateRunDTO dto = new CreateRunDTO();
                dto.setSessionId(sessionId);
                dto.setType(RunEnum.TYPE_AUTO);
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
                break loop;
            }

            // Conditional state update: an already-cancelled/terminal run must not be dragged back to RUNNING by a late wake.
            // The old implementation called updateRun on the whole row, so cancel and suspension races overwrote each other (production run 2995).
            boolean running = runSpi.markRunning(currentRunId, loopCount, maxLoopCount);
            if (!running) {
                log.info("Run {} is no longer active; stopping resumed loop", currentRunId);
                break loop;
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
                String todolistText = null;
                if (messages.isEmpty()) {
                    titleService.generateIfNeeded(ctx, input.text(), sessionId, currentRunId);
                    try {
                        AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                                sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                        if (todoRecord != null && todoRecord.getArtifact() != null) {
                            todolistText = todoRecord.getArtifact();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load todolist for session {}", sessionId, e);
                    }
                    String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
                    }
                    // HistoryResult.currentRunStart is an index relative to the history list; the system prompt has already been prepended here,
                    // so the base offset must be added; otherwise after a wait/clarification resume currentRunStart would point at the system prompt.
                    int historyBase = messages.size();
                    HistoryLoader.HistoryResult history = historyLoader.loadResult(
                            sessionId, currentRunId, input.isClarificationResume());
                    if (history != null) {
                        messages.addAll(history.messages());
                    }
                    currentRunStart = resolveCurrentRunStart(historyBase, history, messages.size());
                }
                messages.add(buildUserMessage(input, attachmentResolver));
                // The todo list is a separate tail system segment (replaced in place, index 0 untouched, keeping the prefix cache stable)
                applyTodolistSuffix(messages, todolistText);
            }

            List<RuntimeTool> tools = ctx != null ? ctx.getTools() : List.of();
            List<ToolDefinitionDTO> toolDefs = runPromptBuilder.buildToolDefinitions(tools);

            // Tail notice block: per-loop budget + hard finalization near the limit.
            // isLastLoop is still decided by n-1 — that is when the loop cap takes effect (see the tool-loop finalization below),
            // while hard finalization leads by FINAL_TURN_INSTRUCTION_LEAD loops; the two are deliberately different.
            boolean isLastLoop = loopCount >= maxLoopCount - 1;
            applyLoopBudget(messages, loopCount, maxLoopCount);
            applySilentTurnNudge(messages, silentTurns);

            AtomicReference<String> turnReasoningRef = new AtomicReference<>("");
            TurnResult turn = runTurn(sessionId, currentRunId, messages, toolDefs, tools, ctx, turnReasoningRef,
                    skipCompaction, currentRunStart, runCompactionAttempts,
                    runCompactionCount, lastRunCompactionLoop, loopCount,
                    currentRun.getRetryAttempt() == null ? 0 : currentRun.getRetryAttempt());

            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) break;

            // Unified finalization: decide the run status from TurnOutcome
            if (turn.content() != null && !turn.content().isBlank()) {
                allContent.append(turn.content());
            }

            // Only a real model success turn (COMPLETE / TOOL_CALLS) resets the backoff counter:
            // convention B — after one success, the next stretch of failures restarts from step 1.
            // COMPACTED / RUN_COMPACTED are only compaction actions, not successful model calls, and must not reset it (otherwise real failures are masked).
            if (turn.outcome() == TurnOutcome.COMPLETE || turn.outcome() == TurnOutcome.TOOL_CALLS) {
                currentRun.setRetryAttempt(0);
                currentRun.setWaitKind(null);
                // Persist immediately and targeted: when a concurrent writer overwrites the whole row with a stale snapshot, it cannot write the just-reset counter back to the old value.
                runSpi.resetRetryState(currentRunId);
            }

            switch (turn.outcome()) {
                case COMPACTED -> {
                    CompactionOutcome outcome = turn.compactionOutcome();
                    if (outcome != CompactionOutcome.COMPLETED) {
                        // No real progress: send this turn with the current context directly; once failures reach the limit this run stops compacting.
                        compactionRetries++;
                        consecutiveCompactionFailures++;
                        if (consecutiveCompactionFailures >= RunnerConstants.MAX_CONSECUTIVE_COMPACTION_FAILURES) {
                            compactionDisabledForRun = true;
                        }
                        log.warn("Compaction did not make progress (outcome={}, attempts={}, consecutiveFailures={}, disabled={}) for run {}; sending oversized context",
                                outcome, compactionRetries, consecutiveCompactionFailures,
                                compactionDisabledForRun, currentRunId);
                        skipCompaction = true;
                        hasToolCalls = true;
                        continue;
                    }
                    consecutiveCompactionFailures = 0;
                    log.info("Compaction completed, reassembling history (attempt {})", compactionRetries);
                    // Replace only the compactable history prefix; the current run's in-memory messages (user / tool loops / observations) are kept as-is.
                    // The old implementation cleared everything and replayed from the DB, while HistoryLoader excludes the current run by default — tool loops lost their context,
                    // and the first turn wrote the current user into the summary and added it again.
                    List<ChatMessageDTO> currentRunTail = currentRunStart >= 0 && currentRunStart <= messages.size()
                            ? new ArrayList<>(messages.subList(currentRunStart, messages.size()))
                            : new ArrayList<>();
                    messages.clear();
                    String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
                    }
                    messages.addAll(historyLoader.load(sessionId, currentRunId));
                    currentRunStart = messages.size();
                    messages.addAll(currentRunTail);
                    refreshTodolistSlot(sessionId, messages);
                    hasToolCalls = true;
                    continue;
                }
                case RUN_COMPACTED -> {
                    if (turn.rebuiltMessages() == null) {
                        consecutiveCompactionFailures++;
                        if (consecutiveCompactionFailures >= RunnerConstants.MAX_CONSECUTIVE_COMPACTION_FAILURES) {
                            compactionDisabledForRun = true;
                        }
                        log.warn("Run compaction returned no rebuilt messages for run {} (consecutiveFailures={}, disabled={}); sending as-is",
                                currentRunId, consecutiveCompactionFailures, compactionDisabledForRun);
                        skipCompaction = true;
                        hasToolCalls = true;
                        continue;
                    }
                    consecutiveCompactionFailures = 0;
                    runCompactionAttempts++;
                    runCompactionCount++;
                    lastRunCompactionLoop = loopCount;
                    log.info("Run compaction applied for run {} (attempt {}, runCount {}): messages={}",
                            currentRunId, runCompactionAttempts, runCompactionCount, turn.rebuiltMessages().size());
                    messages.clear();
                    messages.addAll(turn.rebuiltMessages());
                    currentRunStart = turn.rebuiltCurrentRunStart() != null
                            ? turn.rebuiltCurrentRunStart() : -1;
                    refreshTodolistSlot(sessionId, messages);
                    hasToolCalls = true;
                    continue;
                }
                case RETRY_LATER -> {
                    // Cancel and suspension racing in the same window: an already-cancelled run must not create a wait record
                    if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
                        log.info("Run {} cancelled before retry suspension; not creating wait", currentRunId);
                        break loop;
                    }
                    if (pendingWaitSpi == null) {
                        // Fallback: when there is no suspension SPI (tests / abnormal wiring), degrade to FAILED so the run is at least not silently dropped
                        log.error("PendingWaitSpi unavailable; failing run {} instead of suspending for retry",
                                currentRunId);
                        currentRun.setStatus(RunStatus.FAILED.name());
                        currentRun.setErrorMessage(turn.errorMessage());
                        runSpi.updateRun(currentRun);
                        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                                RuntimeEventDataVO.builder()
                                        .sessionId(sessionId)
                                        .runId(currentRunId)
                                        .errorMessage(turn.errorMessage())
                                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                        break loop;
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
                        break loop;
                    }

                    currentRun.setRetryAttempt(attempt);
                    currentRun.setWaitKind(WaitConstants.KIND_LLM_RETRY);
                    currentRun.setStatus(RunStatus.WAITING.name());
                    // Conditional update: an already-cancelled/terminal run must not be written back to WAITING
                    if (!runSpi.markWaitingForRetry(currentRunId, attempt)) {
                        pendingWaitSpi.cancelPendingByRunId(currentRunId);
                        log.info("Run {} is no longer active; retry suspension aborted", currentRunId);
                        break loop;
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
                    break loop;
                }
                case ERROR -> {
                    // When a cancel arrives during the LLM call, cancel takes precedence over failure
                    if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
                        break loop;
                    }
                    currentRun.setStatus(RunStatus.FAILED.name());
                    runSpi.updateRun(currentRun);
                    eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(currentRunId)
                                    .errorMessage(turn.errorMessage())
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                    break loop;
                }
                case COMPLETE -> {
                    // When a cancel arrives during the LLM call, cancel takes precedence over completion
                    if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
                        break loop;
                    }
                    if (inputManager.hasPending(sessionId)) {
                        hasToolCalls = false;
                        continue;
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
                        silentTurns = currentSilentTurn;
                        if (currentSilentTurn <= maxSilentTurns) {
                            log.warn("Run {} ended a turn without any tool call (silent turn {}/{}, finishReason={}, contentChars={})",
                                    currentRunId, currentSilentTurn, maxSilentTurns,
                                    turn.finishReason(), turn.content() == null ? 0 : turn.content().length());
                            continue;
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
                        break loop;
                    }
                    if (implicitText) {
                        log.warn("Run {} completed by implicit text after tool use (completionProtocol={}, finishReason={}, contentChars={})",
                                currentRunId, completionProtocol, turn.finishReason(), turn.content().length());
                    }
                    // Note: loopCapped must <b>not</b> be set here. Reaching this branch means the model delivered an answer on the last loop,
                    // which is a normal completion, not truncation by the limit. The old implementation also set loopCapped here, so
                    // "finished exactly on the last loop" and "truncated into a half-finished result" were conflated; the task guard
                    // (AgentTaskServiceImpl) then vetoed it on that basis and threw the whole complete output away
                    // — production run 1575 (status=empty/stopReason=POOL_EXHAUSTED, the complete conclusion of a finished pool)
                    // was replied with {"error":"run loop limit reached"} exactly this way.
                    // Real truncation is marked only in the tool-loop branch below.
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
                    break loop;
                }
                case CANCELLED -> {
                    break loop;
                }
                default -> {
                    // TOOL_CALLS — fall through to tool execution below
                }
            }

            // This turn really sent (tool loop): reset the retry counter for this turn; if this run has already failed up to the limit,
            // keep compaction off so every tool loop does not re-attempt a summarization call doomed to fail.
            skipCompaction = compactionDisabledForRun;
            compactionRetries = 0;

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
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setToolCalls(toolCallDTOs);
            // The body (if any) must be kept: otherwise compaction/tail/resume would leave only tool calls, and the model could not see what it said
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
                    parked = true;
                    compactionService.snapshotRunTail(currentRunId, messages, currentRunStart);
                    currentRun.setStatus(RunStatus.AWAITING_USER.name());
                    runSpi.updateRun(currentRun);
                    eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.AWAITING_USER,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(currentRunId)
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                    break loop;
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

            // A turn containing the delegate tool: the outer fiber's batch execution timeout must accommodate the sub-agent's own budget (sub-agents self-terminate on their deadline),
            // otherwise a long sub-agent is interrupted by the default 30s execution timeout → "Tool execution lost"/InterruptedException.
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
                // wait's "result" is only a suspension signal, not that the wait has completed: the run stays RUNNING here,
                // and when due recordWaitToolSucceeded sends SUCCEEDED and backfills elapsed_ms/resumed_at.
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
                break;
            }

            // finish: the model explicitly declares the task complete — the only legal way for an agentic run to end.
            // Must be handled before submitting any tool: other tools in the same turn are not executed (avoiding "producing side effects while wrapping up").
            // Their PENDING records were created by runTurn's PENDING event and must each be closed with a FAILED row,
            // otherwise timeline stays at PENDING forever. This branch also runs before isLastLoop's markLoopCapped,
            // ensuring "finish on the last loop" is a clean completion and is not marked as a loop cap.
            TurnToolCall finishCall = null;
            for (TurnToolCall tcc : turn.toolCalls()) {
                if (FinishConstants.TOOL_NAME.equals(tcc.name())) {
                    finishCall = tcc;
                    break;
                }
            }
            if (finishCall != null) {
                for (TurnToolCall other : turn.toolCalls()) {
                    if (other == finishCall) {
                        continue;
                    }
                    log.warn("Discarding tool call {} from a finish turn (run {})", other.name(), currentRunId);
                    eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(currentRunId)
                                    .toolName(other.name())
                                    .displayNameCn(toolExecutor.resolveDisplayName(other.name(), tools))
                                    .displayNameEn(toolExecutor.resolveDisplayNameEn(other.name(), tools))
                                    .errorMessage("skipped: finish was called in the same turn")
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + other.id()).build()));
                }
                // Close finish's own PENDING record
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .toolName(finishCall.name())
                                .displayNameCn(FinishConstants.DISPLAY_NAME_CN)
                                .displayNameEn(FinishConstants.DISPLAY_NAME)
                                .argumentsJson(finishCall.arguments())
                                .artifact(finishCall.arguments())
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + finishCall.id()).build()));
                String declaredSummary = jsonField(finishCall.arguments(), FinishConstants.JSON_SUMMARY);
                String declaredStatus = jsonField(finishCall.arguments(), FinishConstants.JSON_STATUS);
                String reply = declaredSummary != null && !declaredSummary.isBlank()
                        ? declaredSummary
                        : allContent.toString();
                if (reply != null && !reply.isBlank()) {
                    currentRun.setAssistantReply(reply);
                }
                currentRun.setFinishStatus(declaredStatus);
                currentRun.setStatus(RunStatus.COMPLETED.name());
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .assistantReply(currentRun.getAssistantReply())
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                log.info("Run {} finished by finish tool: declaredStatus={}", currentRunId, declaredStatus);
                break loop;
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

            // If cancelled during tool execution, publish FAILED events and exit.
            // Do not consume CANCELLED_RUNS here: terminal states (run=CANCELLED + terminal event) are handled uniformly by the post-loop finalization,
            // otherwise the run would stay in RUNNING and the frontend would never receive run_cancelled/run_failed.
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
                break;
            }

            // Suspension decision: both clarification (waiting for a person) and wait (waiting for time) break this turn out of the loop instead of feeding the model again.
            // Parse status structurally instead of contains("\"status\":\"awaiting_user\""):
            // that approach depends on the serialized field order and literal, and changing the encoding once silently breaks it —
            // the suspension does not trigger → the run spins.
            RunStatus suspendStatus = null;
            for (TurnToolCall tc : turn.toolCalls()) {
                suspendStatus = SuspenseStatusResolver.resolve(tc.name(), results.getOrDefault(tc.id(), ""));
                if (suspendStatus != null) {
                    break;
                }
            }
            if (suspendStatus != null) {
                parked = true;
                // Suspension tools (wait / ask_clarification) are also a tool loop turn: increment and persist,
                // so the resume uses a new turn_no and does not reuse the interaction and assistant rows from before the suspension,
                // otherwise the reply after suspension would land above the clarification/wait card and scramble the timeline order.
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
                break;
            }

            for (TurnToolCall tc : turn.toolCalls()) {
                String toolResult = results.getOrDefault(tc.id(),
                        RunnerConstants.JSON_ERROR_TOOL_LOST);
                String toolMessage = toolResult;
                if (tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId())) {
                    // The full list has already been delivered through the tail todo slot; the tool receipt keeps only a short confirmation, so one plan does not take up two contexts.
                    toolMessage = RunnerConstants.TODO_WRITE_ACK;
                }
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_TOOL)
                        .setToolCallId(tc.id())
                        .setContent(toolMessage));
                // The SUCCEEDED event was already published in the onEachResult callback; do not repeat it here
            }

            // Append all tool result messages first, then inject screenshot observations uniformly, ensuring the legal OpenAI sequence
            // assistant(tool_calls) → tool* contiguous → user observation (multi-tool batches are not interrupted)
            for (TurnToolCall tc : turn.toolCalls()) {
                if (tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId())) {
                    injectScreenshotObservation(sessionId, currentRunId, messages,
                            results.getOrDefault(tc.id(), RunnerConstants.JSON_ERROR_TOOL_LOST));
                }
            }

            boolean todowriteCalled = turn.toolCalls().stream().anyMatch(tc ->
                    tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId()));
            if (todowriteCalled && !messages.isEmpty()) {
                try {
                    AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                            sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                    String freshTodolist = todoRecord != null && todoRecord.getArtifact() != null
                            ? todoRecord.getArtifact() : null;
                    // Update the tail todo slot in place (do not rewrite messages[0], keeping prefix cache hits)
                    applyTodolistSuffix(messages, freshTodolist);
                } catch (Exception e) {
                    log.warn("Failed to refresh todolist for session {}", sessionId, e);
                }
            }

            // After the last tool loop executes, end directly
            if (isLastLoop) {
                markLoopCapped(currentRun, maxLoopCount);
                String fallback = allContent.length() > 0
                        ? allContent.toString()
                        : RunnerConstants.FALLBACK_COMPLETE_MSG;
                currentRun.setAssistantReply(fallback);
                currentRun.setStatus(RunStatus.COMPLETED.name());
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(currentRunId)
                                .assistantReply(fallback)
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
                break;
            }

            hasToolCalls = true;
            toolsEverExecuted = true;
            silentTurns = 0;
            loopCount++;
        }

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
        return parked;
    }

    /** Uniformly writes the run failure terminal state and emits the event, avoiding repeated data assembly. */
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
    private void markLoopCapped(RunVO run, int maxLoopCount) {
        if (run == null) {
            return;
        }
        run.setLoopCapped(true);
        log.warn("Run {} hit loop cap {} (loopCapped=true, task guard will fail the task)", run.getId(), maxLoopCount);
    }

    /** Re-inject the todo slot from the DB after compaction reassembly, so todos are not lost to summary / tail selection. */
    private void refreshTodolistSlot(Long sessionId, List<ChatMessageDTO> messages) {
        try {
            AgentToolCallRecordVO todo = toolCallRecordSpi.getLatestBySessionAndToolName(
                    sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
            applyTodolistSuffix(messages, todo != null ? todo.getArtifact() : null);
        } catch (Exception e) {
            log.warn("Failed to refresh todolist after compaction for session {}", sessionId, e);
        }
    }

    /**
     * Maintains the todo slot at the tail of the message list in place: locates an existing slot by {@link RunnerConstants#PROMPT_TODOLIST_HEADER}
     * (present −> replace in place; absent −> insert before FINAL_TURN or at the end of the list), avoiding middle shifts that break the prefix cache.
     * Removes the old slot when todolistText is empty. messages[0] and the history prefix stay byte-identical.
     */
    private void applyTodolistSuffix(List<ChatMessageDTO> messages, String todolistText) {
        if (messages == null) {
            return;
        }
        String suffix = runPromptBuilder.buildTodolistSuffix(todolistText);
        int existingSlot = -1;
        for (int i = 0; i < messages.size(); i++) {
            Object content = messages.get(i).getContent();
            if (content instanceof String s && runPromptBuilder.isTodolistSlot(s)) {
                existingSlot = i;
                break;
            }
        }
        if (suffix == null) {
            if (existingSlot >= 0) {
                messages.remove(existingSlot);
            }
            return;
        }
        if (existingSlot >= 0) {
            messages.get(existingSlot).setContent(suffix);
            return;
        }
        // No existing slot: insert before the tail notice block when possible, so the todo is one of the "last visible system" messages
        int insertAt = tailNoticeBlockStart(messages);
        messages.add(insertAt, new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_SYSTEM)
                .setContent(suffix));
    }

    /**
     * Rebuilds the notice block at the tail of the message list: the loop budget notice (every loop) + hard finalization (from when remaining ≤
     * {@link RunnerConstants#FINAL_TURN_INSTRUCTION_LEAD} loops, included every loop).
     *
     * <p>Rebuilt every loop instead of replaced in place: tool loops append assistant(tool_calls)/tool/observation messages to the tail of messages,
     * so the previous loop's budget copy would be pushed into the middle of the history, wasting context and contradicting the new text. Removal uses a full
     * table scan by marker, guaranteeing exactly one copy of the current loop's budget at all times.
     *
     * <p>Hard finalization starts at n-{@code LEAD} instead of waiting for the last loop: finalization is only a hint, not enforced, and models often
     * really wrap up only on the last loop, so 1 loop is too tight to finish (see {@link RunnerConstants#FINAL_TURN_INSTRUCTION_LEAD}).
     * It is injected <b>continuously</b> — once inside that window, n-2 and n-1 carry it too, until this method's per-loop rebuild decides again.
     */
    /**
     * Silent follow-up: when this turn has no tool calls, give the model a tail hint (located by marker, replaced every loop instead of accumulating).
     *
     * <p>Only applies to runs that have already entered the agentic flow — pure chat instances (never used a tool) keep "returning text means done".
     */
    private void applySilentTurnNudge(List<ChatMessageDTO> messages, int silentTurns) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        messages.removeIf(m -> m.getContent() instanceof String t
                && t.startsWith(RunnerConstants.SILENT_TURN_NUDGE_PREFIX));
        if (silentTurns <= 0) {
            return;
        }
        messages.add(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_SYSTEM)
                .setContent(RunnerConstants.SILENT_TURN_NUDGE_PREFIX + RunnerConstants.SILENT_TURN_NUDGE_TEXT));
    }

    private void applyLoopBudget(List<ChatMessageDTO> messages, int loopCount, int maxLoopCount) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        messages.removeIf(m -> {
            Object content = m.getContent();
            if (!(content instanceof String s)) {
                return false;
            }
            return runPromptBuilder.isLoopBudget(s) || RunnerConstants.FINAL_TURN_INSTRUCTION.equals(s);
        });
        int remaining = maxLoopCount - loopCount;
        messages.add(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_SYSTEM)
                .setContent(runPromptBuilder.buildLoopBudget(
                        loopCount + 1, remaining, maxLoopCount)));
        if (remaining <= RunnerConstants.FINAL_TURN_INSTRUCTION_LEAD) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent(RunnerConstants.FINAL_TURN_INSTRUCTION));
        }
    }

    /**
     * Start index of the tail notice block (loop budget / final-turn finalization); the list length (i.e. the end) when there is none.
     * The todo slot is inserted before it, so a mid-loop todo refresh is not pushed into the middle by the notice and loses its "last visible system" effect.
     */
    private int tailNoticeBlockStart(List<ChatMessageDTO> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Object content = messages.get(i).getContent();
            if (!(content instanceof String s)) {
                continue;
            }
            if (runPromptBuilder.isLoopBudget(s) || RunnerConstants.FINAL_TURN_INSTRUCTION.equals(s)) {
                return i;
            }
        }
        return messages.size();
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
        return "{\"error\":\"USER_DENIED\",\"message\":\"User denied this tool call. "
                + "Do not retry this tool in this run.\",\"tool\":\"" + toolName + "\"}";
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
                List<ModelRouteFullVO> routes = resolveRoutes(sessionId, ctx);

                // Image attachment request: only routes that can handle attachments are allowed, avoiding falling to a text-only model (400/silently ignored)
                if (hasAttachmentImage(messages) && !routes.isEmpty()) {
                    List<ModelRouteFullVO> capable = routes.stream()
                            .filter(r -> Boolean.TRUE.equals(r.getSupportsAttachment()))
                            .toList();
                    if (capable.isEmpty()) {
                        // No route can handle images: strip the images and degrade to plain text (does not block the run)
                        log.info("No attachment-capable route, degrading to text: session={}, run={}", sessionId, runId);
                        stripImageParts(messages);
                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                RuntimeEventDataVO.builder()
                                        .sessionId(sessionId).runId(runId)
                                        .response("⚠️ The current route does not support images; continuing with text only")
                                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                        .publishId(UUID.randomUUID().toString()).build()));
                    } else {
                        routes = capable;
                    }
                }

                List<ModelRouteFullVO> activeRoutes = routes;
                fallbackRouteExecutor.execute(activeRoutes, (i, route) -> {
                    if (isRunCancelled(runId) || isSessionCancelled(sessionId)) {
                        cancelled.set(true);
                        throw new RuntimeException("Run cancelled");
                    }
                    // Accumulated independently per route: when the fallback re-runs after the primary route fails,
                    // it must not carry over the previous route's partial body/tool calls/errors.
                    errorRef.set(null);
                    errorRetryableRef.set(true);
                    errorStatusRef.set(null);
                    finishReasonRef.set(null);
                    contentRef.set("");
                    toolCalls.clear();
                    java.util.concurrent.atomic.AtomicBoolean acceptingEvents =
                            new java.util.concurrent.atomic.AtomicBoolean(true);

                    // Project only the request copy: canonical messages keep the full tool results for compaction/snapshots.
                    Map<String, Boolean> compressByTool = new HashMap<>();
                    for (RuntimeTool t : tools) {
                        compressByTool.put(t.getLlmToolName(), t.isCompressEnabled());
                    }
                    List<ChatMessageDTO> projectedMessages = LiveToolResultPolicy.project(messages,
                            properties.getRunner().getLiveToolResultMaxChars(),
                            properties.getRunner().getLiveToolResultBudgetChars(),
                            properties.getRunner().getLiveToolResultKeepRecent(),
                            properties.getRunner().getLiveBrowserFullMaxChars(),
                            compressByTool);

                    // * Compaction check: must use the projected messages actually sent to the model, avoiding canonical tool results inflating the estimate.
                    // Triggering looks only at input; skipCompaction is true only on a loop where "retries are exhausted and compaction is proven not to help".
                    CompactionService.CompactionDecision decision = compactionService.evaluate(
                            projectedMessages, route, toolDefs);
                    long maxInputTokens = route.getMaxInputTokens() != null
                            ? route.getMaxInputTokens() : RunnerConstants.DEFAULT_MAX_INPUT_TOKENS;
                    int nominalChatMax = compactionService.resolveNominalChatMaxOutput(
                            instanceMaxOutput, route.getMaxOutputTokens());
                    int requestMax = compactionService.resolveRequestMaxOutput(
                            maxInputTokens, decision == null ? 0 : decision.estimated(), nominalChatMax);

                    // Output space is already insufficient: compact first; if compaction cannot help, fail directly and never send a request doomed to 400.
                    boolean outputExhausted = requestMax < RunnerConstants.MIN_CHAT_OUTPUT_TOKENS;
                    outputExhaustedRef.set(outputExhausted);
                    if (!skipCompaction && (outputExhausted
                            || (decision != null && decision.shouldCompact()))) {
                        compactionTriggered.set(true);
                        compactionDecisionRef.set(decision);
                        return null;
                    }
                    if (outputExhausted) {
                        // Compaction has been judged unable to help (skipCompaction): prefer a fallback route with a larger context first;
                        // if even the last route cannot fit it, fail directly and mark it non-retryable (switching routes/retrying will not help).
                        if (i < activeRoutes.size() - 1) {
                            throw new RouteAttemptException(
                                    RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG, null, true);
                        }
                        errorRef.set(RunnerConstants.RUN_CONTEXT_OVERFLOW_MSG);
                        errorRetryableRef.set(false);
                        return null;
                    }

                    String apiKey = resolveApiKey(route);
                    ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                            .setModel(route.getModelName())
                            .setStream(true)
                            .setMessages(projectedMessages);
                    if (!toolDefs.isEmpty()) {
                        request.setTools(toolDefs);
                    }
                    // Sampling parameter wiring: global defaults + instance config override.
                    llmRequestConfigurer.applyChat(request, instanceConfig);
                    // Reasoning effort: the run's pinned level + the effective level/Qwen budget driven by this turn's requestMax (V118).
                    // A manually selected level only drops (never rises) when output space is insufficient, so the whole budget is not spent on reasoning.
                    ReasoningEffortResolver.Resolved reasoning = reasoningEffortResolver.resolve(
                            route, route.getConfig(), requestMax, ctx.getReasoningEffort());
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
                            route.getCompany(), route.getBaseUrl(), apiKey, route.getModelName(),
                            request,
                            event -> {
                                // After a timeout/exception the old stream may still emit events; reject writes so the fallback's result is not polluted.
                                if (!acceptingEvents.get()) return;
                                switch (event) {
                                    case LLMEvent.TextDelta t -> {
                                        contentRef.updateAndGet(c -> c + t.text());
                                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                                RuntimeEventDataVO.builder()
                                                        .sessionId(sessionId).runId(runId).response(t.text()).build()));
                                    }
                                    case LLMEvent.ReasoningDelta r -> {
                                        reasoningRef.updateAndGet(c -> c + r.text());
                                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                                RuntimeEventDataVO.builder()
                                                        .sessionId(sessionId).runId(runId)
                                                        .response(r.text())
                                                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                                                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                                        .publishId(UUID.randomUUID().toString()).build()));
                                    }
                                    case LLMEvent.ToolInputStart start -> {
                                        // Arguments may take tens of seconds to stream (e.g. a whole document), so give the frontend an immediate
                                        // "generating call arguments" feedback first; the approval card is still created only after the arguments are complete.
                                        eventPublisher.publishEvent(new RuntimeEventVO(
                                                FlowEventType.REASONING_TOKEN,
                                                RuntimeEventDataVO.builder()
                                                        .sessionId(sessionId)
                                                        .runId(runId)
                                                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + start.id())
                                                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_INPUT_PREPARING)
                                                        .toolName(start.name())
                                                        .displayNameCn(toolExecutor.resolveDisplayName(start.name(), tools))
                                                        .displayNameEn(toolExecutor.resolveDisplayNameEn(start.name(), tools))
                                                        .response("⚙️ " + toolExecutor.resolveDisplayName(start.name(), tools)
                                                                + ": generating call arguments…").build()));
                                    }
                                    case LLMEvent.ToolCall tc -> {
                                        String callPublishId = RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id();
                                        toolCalls.add(new TurnToolCall(tc.id(), tc.name(), tc.arguments()));
                                        eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.PENDING,
                                                RuntimeEventDataVO.builder()
                                                        .sessionId(sessionId).runId(runId)
                                                        .turnNo(loopCount)
                                                        .toolName(tc.name())
                                                        .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), tools))
                                                        .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), tools))
                                                        .argumentsJson(tc.arguments())
                                                        .publishId(callPublishId).build()));
                                        // finish's summary is the final answer: when the body is empty, push it to the frontend in real time as the assistant body.
                                        // Otherwise the frontend would show only a finish tool card with a blank answer; do not resend when the body has already streamed.
                                        if (FinishConstants.TOOL_NAME.equals(tc.name())) {
                                            String summary = jsonField(tc.arguments(), FinishConstants.JSON_SUMMARY);
                                            if (summary != null && !summary.isBlank() && contentRef.get().isEmpty()) {
                                                contentRef.set(summary);
                                                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                                        RuntimeEventDataVO.builder()
                                                                .sessionId(sessionId).runId(runId)
                                                                .response(summary).build()));
                                            }
                                        }
                                    }
                                    case LLMEvent.Finish f -> finishReasonRef.set(f.reason());
                                    case LLMEvent.Error e -> {
                                        errorRef.set(e.message());
                                        errorRetryableRef.set(e.retryable());
                                        errorStatusRef.set(e.httpStatus());
                                    }
                                    default -> {
                                    }
                                }
                            },
                            new LlmInteractionMeta().setRunId(runId).setSessionId(sessionId)
                                    .setSuperAgentId(ctx.getSession() == null ? null : ctx.getSession().getSuperAgentId())
                                    .setSessionType(ctx.getSession() == null ? null : ctx.getSession().getSessionType())
                                    .setThreadId(ctx.getSession() == null ? null : ctx.getSession().getThreadId())
                                    .setInteractionType(LlmInteractionType.CHAT_REPLY)
                                    .setTurnNo(loopCount)
                                    .setAttemptNo(i)
                                    .setRetryAttempt(retryMark)
                                    .setModelRouteId(route.getId())
                                    .setEstimatedPromptTokens((int) compactionService.estimatePromptTokens(
                                            projectedMessages, toolDefs)));

                    future.whenComplete((v, ex) -> streamDone.countDown());

                    try {
                        long turnTimeout = properties.getRunner().getTurnTimeout().getSeconds();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeout);
                        while (!streamDone.await(1, TimeUnit.SECONDS)) {
                            if (isRunCancelled(runId) || isSessionCancelled(sessionId)) {
                                cancelled.set(true);
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

                    // A timeout/underlying exception completes the future exceptionally; the old implementation waited only on the latch, so a timeout was treated as a normal finish.
                    if (future.isCompletedExceptionally()) {
                        acceptingEvents.set(false);
                        String failure = futureFailureMessage(future);
                        errorRef.compareAndSet(null, failure);
                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                RuntimeEventDataVO.builder()
                                        .sessionId(sessionId).runId(runId)
                                        .response("⚠️ " + route.getModelName() + ": " + failure + ", trying fallback route...")
                                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                        .publishId(UUID.randomUUID().toString()).build()));
                        throw new RouteAttemptException("Route failed: " + failure, null, true);
                    }

                    String error = errorRef.get();
                    if (error != null) {
                        acceptingEvents.set(false);
                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                RuntimeEventDataVO.builder()
                                        .sessionId(sessionId).runId(runId)
                                        .response("⚠️ " + route.getModelName() + ": " + error + ", trying fallback route...")
                                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                        .publishId(UUID.randomUUID().toString()).build()));
                        throw new RouteAttemptException("Route failed: " + error,
                                errorStatusRef.get(), errorRetryableRef.get());
                    }

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
                    RunCompactionResult runResult = compactionService.compactRun(sessionId, runId, ctx,
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
                return TurnResult.cancelled(getTurnContent(contentRef), toolCalls);
            }

            String error = errorRef.get();
            // Transient failure with no output yet → graded retry: the first few steps sleep in place, later steps suspend (WAITING + kind=LLM_RETRY)
            if (error != null && LlmRetryPolicy.isTransient(errorStatusRef.get(), errorRetryableRef.get())
                    && getTurnContent(contentRef).isEmpty() && toolCalls.isEmpty()
                    && failureNo <= maxAttempts
                    && !LlmRetryPolicy.overTotalWindow(retryCfg, failureNo)) {
                if (LlmRetryPolicy.inline(retryCfg, failureNo)) {
                    continue;
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
                // Tool loop: even if the body was truncated, the tool arguments are already complete or were never usable, so continue as a normal tool loop
                return TurnResult.toolCalls(getTurnContent(contentRef), toolCalls).withFinishReason(finishReason);
            }
            // There is a body but no LLMEvent.Finish: the stream was truncated / the connection closed early,
            // and the partial body must not be treated as a complete answer. A completely empty stream keeps the original semantics, so eventless paths are not misjudged as errors.
            if ((finishReason == null || finishReason.isBlank())
                    && !getTurnContent(contentRef).isEmpty()) {
                return TurnResult.error("LLM stream ended without finish_reason (missing LLMEvent.Finish); "
                        + "refusing to treat partial output as completion");
            }
            // No tool calls yet "abnormal finish" → cannot count as complete (otherwise a partial answer would be marked COMPLETED)
            if (FinishReason.LENGTH.equals(finishReason)) {
                return TurnResult.error("output truncated by max_tokens (finish_reason=length): "
                        + "raise max_tokens or shorten the requested answer")
                        .withFinishReason(finishReason);
            }
            if (FinishReason.CONTENT_FILTER.equals(finishReason)) {
                return TurnResult.error("output blocked by content filter (finish_reason=content_filter)")
                        .withFinishReason(finishReason);
            }
            return TurnResult.complete(getTurnContent(contentRef)).withFinishReason(finishReason);
        }
    }

    /** Reads a string field from tool input; missing/invalid always returns null (finalization logic must tolerate dirty input). */
    private static String jsonField(String argumentsJson, String field) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(argumentsJson).path(field);
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getTurnContent(AtomicReference<String> contentRef) {
        return contentRef.get();
    }

    /**
     * Wait duration before retry number {@code attempt}: {@code base × 2^(attempt-1)}, capped at {@code MAX}.
     *
     * <p>1-based attempt: the first retry waits {@code BASE}, then 2×/4× and so on.
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
     * Builds the user message: plain text when there are no attachments, otherwise OpenAI-compatible content parts (text+images).
     * Attachments carry only fileKey; the data URL is lazily read back through {@code attachmentResolver} only when the message is assembled.
     */
    static ChatMessageDTO buildUserMessage(SessionInputManager.InputMessage input,
                                           ChatAttachmentResolver attachmentResolver) {
        ChatMessageDTO msg = new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER);
        if (input == null || input.attachments() == null || input.attachments().isEmpty()) {
            String text = input != null && input.text() != null ? input.text() : "";
            return msg.setContent(text);
        }
        List<ChatMessagePartDTO> parts = new ArrayList<>();
        if (input.text() != null && !input.text().isBlank()) {
            parts.add(new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_TEXT).setText(input.text()));
        }
        int count = 0;
        for (PreparedAttachment att : input.attachments()) {
            if (att == null || att.fileKey() == null || att.fileKey().isBlank()) {
                continue;
            }
            if (count >= MAX_USER_ATTACHMENTS) {
                break;
            }
            String dataUrl = attachmentResolver.toDataUrl(att.fileKey());
            if (dataUrl == null || dataUrl.isBlank()) {
                continue;
            }
            ChatMessagePartDTO.ImageUrl imageUrl = new ChatMessagePartDTO.ImageUrl().setUrl(dataUrl);
            parts.add(new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_IMAGE_URL).setImageUrl(imageUrl));
            count++;
        }
        return msg.setContent(parts);
    }

    /** Whether the current turn has image attachments: the last USER message content is parts and contains image_url. */
    static boolean hasAttachmentImage(List<ChatMessageDTO> messages) {
        return ScreenshotObservationSupport.hasAttachmentImage(messages);
    }

    /**
     * Degradation when no route supports images: collapse the last USER message's parts into a plain text String
     * (concatenate text parts, using "[image]" as a placeholder when empty, so an image-only message does not become empty content sent to the model).
     */
    static void stripImageParts(List<ChatMessageDTO> messages) {
        ScreenshotObservationSupport.stripImageParts(messages);
    }

    /**
     * When a browser screenshot tool result contains data.screenshot → append a USER observation message (text + image_url dataURL).
     * Delegates to {@link ScreenshotObservationSupport} (shared logic and injection cap with sub-agents).
     */
    private void injectScreenshotObservation(Long sessionId, Long runId,
                                             List<ChatMessageDTO> messages, String toolResult) {
        ScreenshotObservationSupport support = screenshotObservationSupport;
        if (support == null) {
            support = new ScreenshotObservationSupport(properties.getRunner().getMaxScreenshotsPerRun(), attachmentResolver);
            screenshotObservationSupport = support;
        }
        support.injectScreenshotObservation(sessionId, runId, messages, toolResult);
    }

    private List<ModelRouteFullVO> resolveRoutes(Long sessionId, KernelContext ctx) {
        if (ctx != null && (ctx.getModelRoute() != null || ctx.getFallbackRoutes() != null)) {
            return routeListBuilder.fromContext(ctx);
        }
        var session = sessionSpi.getSession(sessionId);
        if (session != null && session.getAgentInstanceId() != null) {
            return routeListBuilder.fromInstance(session.getAgentInstanceId());
        }
        return List.of();
    }

    private String resolveApiKey(ModelRouteFullVO route) {
        if (route.getApiKeyId() == null) return "";
        try {
            return apiKeySpi.getApiKeyValue(route.getApiKeyId());
        } catch (Exception e) {
            log.warn("Failed to resolve API key id={}", route.getApiKeyId(), e);
            return "";
        }
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
