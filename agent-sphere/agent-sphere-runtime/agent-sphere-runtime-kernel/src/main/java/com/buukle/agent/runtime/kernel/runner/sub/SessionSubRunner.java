package com.buukle.agent.runtime.kernel.runner.sub;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.common.constant.ToolApprovalDecision;
import com.buukle.agent.common.constant.ToolSecurityLevel;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.enums.SubRunStatus;
import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.spi.AgentSubAgentRunSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.SubRunPolicy;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.port.vo.ToolDeniedResultVO;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.loop.LoopLimitResolver;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolAccessGuard;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import com.buukle.agent.runtime.kernel.util.ScreenshotObservationSupport;
import com.buukle.agent.util.json.ToolCallReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SubRun nested ReAct executor (generic): when the main loop calls a sub-run tool, it starts a child agent
 * inside the session, reusing the session's model route and the budget/whitelist/naming given by the policy ({@link SubRunPolicy}),
 * and returns the final result to the main loop as a tool result. The policy owns host differences; this class has no host coupling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSubRunner {

    private final KernelLlmService kernelLlmService;
    private final FallbackRouteExecutor fallbackRouteExecutor;
    private final RouteListBuilder routeListBuilder;
    private final ApiKeySpi apiKeySpi;
    private final ToolExecutor toolExecutor;
    private final RunPromptBuilder runPromptBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redissonClient;
    private final AgentRuntimeProperties properties;
    private final SubRunPolicy subRunPolicy;
    private final AgentSubAgentRunSpi subAgentRunSpi;
    /** Optional: unified Timeline index (when absent the sub-agent header row is skipped; functionality is unaffected). */
    private final ObjectProvider<AgentTimelineSpi> timelineSpiProvider;
    private final ChatAttachmentResolver attachmentResolver;
    private final LlmRequestConfigurer llmRequestConfigurer;
    private final ToolApprovalGrantSpi toolApprovalGrantSpi;
    private final ToolApprovalService toolApprovalService;
    /** Browser screenshot observation injection support (shared by main/sub agents, with shared ordering/count); depends on attachmentResolver and is initialized lazily. */
    private volatile ScreenshotObservationSupport screenshotObservationSupport;

    private ScreenshotObservationSupport screenshotSupport() {
        ScreenshotObservationSupport support = screenshotObservationSupport;
        if (support == null) {
            support = new ScreenshotObservationSupport(properties.getRunner().getMaxScreenshotsPerRun(), attachmentResolver);
            screenshotObservationSupport = support;
        }
        return support;
    }

    /** ToolExecutor sub-run branch entry point. */
    public String execute(RuntimeTool subRunTool, String argsJson,
                          SubRunExecutionContext parentCtx, List<RuntimeTool> sessionTools) {
        SubRunPolicy policy = subRunPolicy;
        if (policy == null || !policy.isEnabled()) {
            return policy != null ? policy.errorDisabled() : "{\"error\":\"Sub-run disabled\"}";
        }
        long subRunToolId = policy.toolId(subRunTool);
        String displayName = policy.displayName(subRunTool);
        // prepare
        SubRunPolicy.PreparedSubRun prep = policy.prepare(subRunTool, subRunToolId, parentCtx);
        if (!prep.ready()) {
            return prep.error();
        }
        SubRunExecutionContext childCtx = prep.childCtx();

        String rendered;
        try {
            rendered = policy.renderPrompt(subRunTool, argsJson, parentCtx);
        } catch (InvalidSubRunDefinition e) {
            return policy.errorRenderFailed(e.getMessage());
        }

        List<RuntimeTool> subTools = filterTools(sessionTools);

        publishReasoning(policy.textStarted(displayName, prep.depth()), subRunToolId);

        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole(LlmRoles.SYSTEM).setContent(buildSystemPrompt(childCtx, subTools, subRunTool)));
        messages.add(new ChatMessageDTO().setRole(LlmRoles.USER).setContent(rendered));
        String contactEnvelope = policy.contactEnvelope(subRunTool, argsJson);
        if (StringUtils.hasText(contactEnvelope)) {
            messages.add(new ChatMessageDTO().setRole(LlmRoles.USER).setContent(contactEnvelope));
        }
        log.debug("Sub-run dispatched: toolRef={} promptChars={} envelopeBytes={}",
                subRunTool != null ? subRunTool.getToolRef() : null,
                rendered != null ? rendered.length() : 0,
                StringUtils.hasText(contactEnvelope) ? contactEnvelope.length() : 0);

        // Generic sub-agent run: created on entry (agentType comes from the policy), status updated on exit;
        // parent_run_id is set to the owner of the current context (null at top level), forming the nested sub-agent chain.
        Long subAgentRunId = startSubAgentRun(childCtx, policy, subRunTool, subRunToolId, displayName);
        // Inner tool execution context: owner points to the current sub-run, so a nested delegate's parent_run_id points to it
        SubRunExecutionContext innerCtx = childCtx.child(childCtx.getDepth(), childCtx.getToolRefAncestry(),
                childCtx.getParentToolCallId(), subAgentRunId);

        StringBuilder allContent = new StringBuilder();
        Duration timeout = policy.executionTimeout();
        Instant deadline = Instant.now().plus(timeout != null && !timeout.isZero()
                ? timeout : Duration.ofMinutes(10));
        long turnTimeout = properties.getRunner().getTurnTimeout().getSeconds();

        // Sub-agents reuse the same instance-level loop limit (same as the main loop, highest priority); when unset, fall back to the delegate config and do not inherit the task raise
        Integer instanceLoopLimit = childCtx.getKernelContext() != null
                && childCtx.getKernelContext().getAgentInstance() != null
                ? childCtx.getKernelContext().getAgentInstance().getMaxLoopCount() : null;
        LoopLimitResolver.ResolvedLoopLimit loopLimit = LoopLimitResolver.resolveSub(
                instanceLoopLimit, properties.getDelegate().getMaxSubLoopCount());
        log.info("Sub-run session={} run={} source={}, effective loop limit={}",
                childCtx.getSessionId(), childCtx.getRunId(), loopLimit.source(), loopLimit.limit());

        for (int loop = 0; loop < loopLimit.limit(); loop++) {
            if (cancelled(childCtx) || Thread.interrupted()) {
                finishSubAgentRun(subAgentRunId, SubRunStatus.CANCELLED);
                return policy.errorCancelled();
            }
            if (Instant.now().isAfter(deadline)) {
                publishReasoning(policy.textTimeout(displayName), subRunToolId);
                finishSubAgentRun(subAgentRunId, SubRunStatus.TIMEOUT);
                return policy.errorTimeout();
            }
            TurnResult turn = turn(messages, subTools, innerCtx, turnTimeout, subRunToolId,
                    subRunTool.getDisplayName(), subAgentRunId);
            if (turn.cancelled()) {
                finishSubAgentRun(subAgentRunId, SubRunStatus.CANCELLED);
                return policy.errorCancelled();
            }
            if (turn.error() != null) {
                publishReasoning(policy.textFailed(displayName), subRunToolId);
                finishSubAgentRun(subAgentRunId, SubRunStatus.FAILED);
                return policy.errorExecutionFailed(turn.error());
            }
            if (turn.content() != null) {
                allContent.append(turn.content());
            }
            if (turn.toolCalls().isEmpty()) {
                publishReasoning(policy.textCompleted(displayName), subRunToolId);
                finishSubAgentRun(subAgentRunId, SubRunStatus.COMPLETED);
                return truncate(allContent.toString(), policy.maxResultChars());
            }
            // Record each tool result of this turn (screenshot observations are injected after all tool messages are appended, so the assistant.tool_calls ↔ tool pairing is not broken)
            executeToolCalls(turn.toolCalls(), turn.reasoning(), messages, subTools, innerCtx, childCtx,
                    subAgentRunId, subRunToolId, policy);
        }
        publishReasoning(policy.textSubLoopCapped(displayName), subRunToolId);
        finishSubAgentRun(subAgentRunId, SubRunStatus.FAILED);
        String finalValue = allContent.length() > 0 ? allContent.toString() : RunnerConstants.FALLBACK_COMPLETE_MSG;
        return truncate(normalizeStructuredOutput(finalValue), policy.maxResultChars());
    }

    /** Creates the sub-agent run row and its timeline header; failures are logged, never fatal. */
    private Long startSubAgentRun(SubRunExecutionContext childCtx, SubRunPolicy policy, RuntimeTool subRunTool,
                                  long subRunToolId, String displayName) {
        Long subAgentRunId = null;
        try {
            AgentSubAgentRunVO subRun = subAgentRunSpi.start(childCtx.getSessionId(), childCtx.getRunId(),
                    childCtx.getOwnerSubAgentRunId(),
                    childCtx.getParentToolCallId(), policy.agentType(), policy.toolRef(subRunToolId), displayName);
            subAgentRunId = subRun != null ? subRun.getId() : null;
        } catch (Exception e) {
            log.warn("Failed to start sub-agent run", e);
        }
        // Unified Timeline: sub-agent header row (body is lazily resolved from agent_sub_agent_run; here we only mark and seal it)
        try {
            AgentTimelineSpi ts = timelineSpiProvider.getIfAvailable();
            if (ts != null && subAgentRunId != null) {
                ts.record(childCtx.getSessionId(), childCtx.getRunId(), TimelineKind.SUBAGENT.getCode(), null,
                        TimelineState.RUNNING.getCode(), displayName,
                        null, null, null, subAgentRunId, null);
            }
        } catch (Exception e) {
            log.warn("Timeline sub-agent header row failed: {}", e.getMessage());
        }
        return subAgentRunId;
    }

    /** Executes every tool call of one sub-agent turn and appends the paired tool messages. */
    private void executeToolCalls(List<TurnToolCall> toolCalls, String reasoning, List<ChatMessageDTO> messages,
                                  List<RuntimeTool> subTools, SubRunExecutionContext innerCtx,
                                  SubRunExecutionContext childCtx, Long subAgentRunId, long subRunToolId,
                                  SubRunPolicy policy) {
        Map<String, String> toolResults = new java.util.HashMap<>();
        for (TurnToolCall tc : toolCalls) {
            if (!containsTool(subTools, tc.name())) {
                messages.add(assistantToolCall(tc));
                messages.add(new ChatMessageDTO().setRole(LlmRoles.TOOL).setToolCallId(tc.id())
                        .setContent(policy.errorNotAllowed(tc.name())));
                continue;
            }
            messages.add(assistantToolCallWithReasoning(tc, reasoning));
            String publishId = policy.publishIdPrefix() + subRunToolId + "-" + tc.id();
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                    RuntimeEventDataVO.builder()
                            .sessionId(childCtx.getSessionId())
                            .runId(childCtx.getRunId())
                            .toolName(tc.name())
                            .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                            .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                            .subAgentRunId(subAgentRunId)
                            .parentToolCallId(childCtx.getParentToolCallId())
                            .argumentsJson(tc.arguments())
                            .publishId(publishId).build()));
            String result;
            try {
                RuntimeTool executed = subTools.stream()
                        .filter(t -> tc.name().equals(t.getLlmToolName()))
                        .findFirst().orElse(null);
                String runMode = innerCtx.getKernelContext() != null
                        && innerCtx.getKernelContext().getRun() != null
                        ? innerCtx.getKernelContext().getRun().getRunMode() : null;
                String accessDenied = ToolAccessGuard.denyReason(executed, tc.arguments(), runMode);
                boolean approvalDenied = false;
                if (accessDenied == null && executed != null
                        && ToolSecurityLevel.from(executed.getSecurityLevel()).requiresApproval()) {
                    if (toolApprovalGrantSpi.isDenied(childCtx.getSessionId(), childCtx.getRunId(),
                            executed.getToolRef())) {
                        approvalDenied = true;
                    } else if (!toolApprovalGrantSpi.isGranted(childCtx.getSessionId(), childCtx.getRunId(),
                            executed.getToolRef(), executed.getApprovalScope())) {
                        // Sub-agents execute synchronously and have no suspend/resume channel, so they cannot request user approval.
                        approvalDenied = true;
                    }
                }
                if (accessDenied != null) {
                    result = accessDenied;
                } else if (approvalDenied) {
                    result = ToolDeniedResultVO.of(tc.name()).toString();
                } else {
                    result = toolExecutor.execute(tc, innerCtx, subTools);
                }
                eventPublisher.publishEvent(new RuntimeEventVO(
                        approvalDenied || accessDenied != null ? ToolCallStatus.FAILED : ToolCallStatus.SUCCEEDED,
                        RuntimeEventDataVO.builder()
                                .sessionId(childCtx.getSessionId())
                                .runId(childCtx.getRunId())
                                .toolName(tc.name())
                                .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                                .subAgentRunId(subAgentRunId)
                                .parentToolCallId(childCtx.getParentToolCallId())
                                .artifact(result)
                                .errorMessage(approvalDenied ? "User denied tool execution"
                                        : (accessDenied != null ? "Read-only tool access denied" : null))
                                .publishId(publishId).build()));
            } catch (Exception e) {
                result = "{\"error\":\"" + e.getMessage() + "\"}";
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                        RuntimeEventDataVO.builder()
                                .sessionId(childCtx.getSessionId())
                                .runId(childCtx.getRunId())
                                .toolName(tc.name())
                                .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                                .subAgentRunId(subAgentRunId)
                                .parentToolCallId(childCtx.getParentToolCallId())
                                .errorMessage(e.getMessage())
                                .publishId(publishId).build()));
            }
            messages.add(new ChatMessageDTO().setRole(LlmRoles.TOOL).setToolCallId(tc.id()).setContent(result));
            toolResults.put(tc.id(), result);
        }
        // Append all tool result messages first, then inject screenshot observations uniformly, so each tool message stays adjacent to its assistant.tool_calls and they do not interrupt each other
        for (TurnToolCall tc : toolCalls) {
            screenshotSupport().injectScreenshotObservation(childCtx.getSessionId(), subAgentRunId, messages,
                    toolResults.getOrDefault(tc.id(), ""));
        }
    }

    private void finishSubAgentRun(Long subAgentRunId, SubRunStatus status) {
        if (subAgentRunId == null) {
            return;
        }
        try {
            subAgentRunSpi.finish(subAgentRunId, status.name());
            AgentTimelineSpi ts = timelineSpiProvider.getIfAvailable();
            if (ts != null) {
                String state = switch (status) {
                    case COMPLETED -> TimelineState.COMPLETED.getCode();
                    case TIMEOUT -> TimelineState.TIMEOUT.getCode();
                    case CANCELLED -> TimelineState.CANCELLED.getCode();
                    default -> TimelineState.FAILED.getCode();
                };
                ts.updateBySubAgentRun(subAgentRunId, state);
            }
        } catch (Exception e) {
            log.warn("Failed to finish sub-agent run {}", subAgentRunId, e);
        }
    }

    private TurnResult turn(List<ChatMessageDTO> messages, List<RuntimeTool> subTools,
                            SubRunExecutionContext ctx, long turnTimeout, long subRunToolId, String displayName,
                            Long subAgentRunId) {
        AtomicReference<String> contentRef = new AtomicReference<>("");
        AtomicReference<String> reasoningRef = new AtomicReference<>("");
        List<TurnToolCall> toolCalls = new CopyOnWriteArrayList<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        AtomicReference<Boolean> cancelledRef = new AtomicReference<>(false);
        List<ModelRouteFullVO> routes = resolveRoutes(ctx);
        if (routes.isEmpty()) {
            return TurnResult.errorResult("no model route available");
        }
        // Image attachments/screenshot observations: no image-capable route → strip images and degrade to plain text (same as the main agent; do not block)
        degradeUnsupportedImages(routes, messages, ctx, subAgentRunId);
        try {
            fallbackRouteExecutor.execute(routes, (i, route) -> {
                if (cancelled(ctx)) {
                    cancelledRef.set(true);
                    throw new RuntimeException("Sub Agent cancelled");
                }
                String apiKey = resolveApiKey(route);
                ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                        .setModel(route.getModelName())
                        .setStream(true)
                        .setMessages(new ArrayList<>(messages));
                List<ToolDefinitionDTO> toolDefs = runPromptBuilder.buildToolDefinitions(subTools);
                if (!toolDefs.isEmpty()) {
                    request.setTools(toolDefs);
                }
                // Sampling parameter wiring: global fallback + instance config override; reasoning effort follows the main run's pin.
                String instanceConfig = ctx.getKernelContext() != null
                        && ctx.getKernelContext().getAgentInstance() != null
                        ? ctx.getKernelContext().getAgentInstance().getConfig() : null;
                llmRequestConfigurer.applyChat(request, instanceConfig);
                if (ctx.getKernelContext() != null) {
                    if (ctx.getKernelContext().getReasoningEffort() != null) {
                        request.setReasoningEffort(ctx.getKernelContext().getReasoningEffort());
                    }
                    if (ctx.getKernelContext().getReasoningBudgetTokens() != null) {
                        request.setThinkingBudget(ctx.getKernelContext().getReasoningBudgetTokens());
                    }
                }
                CountDownLatch done = new CountDownLatch(1);
                CompletableFuture<Void> future = kernelLlmService.stream(
                        route.getCompany(), route.getBaseUrl(), apiKey, route.getModelName(), request,
                        event -> handleLlmEvent(event, contentRef, reasoningRef, toolCalls, errorRef,
                                ctx, subTools, subAgentRunId, subRunToolId, displayName),
                        new LlmInteractionMeta().setRunId(ctx.getRunId()).setSessionId(ctx.getSessionId())
                                .setSubAgentRunId(subAgentRunId)
                                .setInteractionType(subRunPolicy.interactionType()));
                future.whenComplete((v, ex) -> done.countDown());
                if (awaitTurn(future, done, ctx, turnTimeout)) {
                    cancelledRef.set(true);
                    throw new RuntimeException("Sub Agent cancelled");
                }
                String error = errorRef.get();
                if (error != null) {
                    throw new RuntimeException("Sub Agent LLM error: " + error);
                }
                return null;
            });
        } catch (Exception e) {
            if (!cancelledRef.get()) {
                errorRef.set(e.getMessage());
            }
        }
        if (cancelledRef.get()) {
            return TurnResult.cancelledResult();
        }
        return new TurnResult(contentRef.get(), toolCalls, errorRef.get(), false, reasoningRef.get());
    }

    private List<ModelRouteFullVO> resolveRoutes(SubRunExecutionContext ctx) {
        List<ModelRouteFullVO> routes = routeListBuilder.fromContext(ctx.getKernelContext());
        if (routes.isEmpty() && ctx.getKernelContext() != null
                && ctx.getKernelContext().getAgentInstance() != null) {
            routes = routeListBuilder.fromInstance(ctx.getKernelContext().getAgentInstance().getId());
        }
        return routes;
    }

    /** Degrades to text when the selected routes cannot accept image attachments. */
    private void degradeUnsupportedImages(List<ModelRouteFullVO> routes, List<ChatMessageDTO> messages,
                                          SubRunExecutionContext ctx, Long subAgentRunId) {
        if (!ScreenshotObservationSupport.hasAttachmentImage(messages)) {
            return;
        }
        boolean anyCapable = routes.stream().anyMatch(r -> Boolean.TRUE.equals(r.getSupportsAttachment()));
        if (anyCapable) {
            return;
        }
        log.info("No attachment-capable sub-route, degrading to text: session={}, sub={}",
                ctx.getSessionId(), subAgentRunId);
        ScreenshotObservationSupport.stripImageParts(messages);
        eventPublisher.publishEvent(new RuntimeEventVO(
                FlowEventType.REASONING_TOKEN,
                RuntimeEventDataVO.builder()
                        .sessionId(ctx.getSessionId())
                        .runId(ctx.getRunId())
                        .subAgentRunId(subAgentRunId)
                        .parentToolCallId(ctx.getParentToolCallId())
                        .response("⚠️ The current route does not support images; continuing with text only")
                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                        .publishId(UUID.randomUUID().toString()).build()));
    }

    private void handleLlmEvent(LLMEvent event, AtomicReference<String> contentRef,
                                AtomicReference<String> reasoningRef, List<TurnToolCall> toolCalls,
                                AtomicReference<String> errorRef, SubRunExecutionContext ctx,
                                List<RuntimeTool> subTools, Long subAgentRunId, long subRunToolId,
                                String displayName) {
        switch (event) {
            case LLMEvent.TextDelta t -> onTextDelta(t, contentRef, ctx, subAgentRunId, subRunToolId);
            case LLMEvent.ReasoningDelta r ->
                    onReasoningDelta(r, reasoningRef, ctx, subAgentRunId, subRunToolId, displayName);
            case LLMEvent.ToolCall tc -> onToolCall(tc, toolCalls, ctx, subTools, subAgentRunId, subRunToolId);
            case LLMEvent.Error e -> errorRef.set(e.message());
            default -> {
            }
        }
    }

    private void onTextDelta(LLMEvent.TextDelta delta, AtomicReference<String> contentRef,
                             SubRunExecutionContext ctx, Long subAgentRunId, long subRunToolId) {
        contentRef.updateAndGet(c -> c + delta.text());
        // Publish sub-agent model replies as they stream (for the frontend sub-card to show the reply).
        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                RuntimeEventDataVO.builder()
                        .sessionId(ctx.getSessionId())
                        .runId(ctx.getRunId())
                        .nodeName(subRunPolicy.nodeNamePrefix() + subRunToolId)
                        .subAgentRunId(subAgentRunId)
                        .parentToolCallId(ctx.getParentToolCallId())
                        .response(delta.text())
                        .publishId(subRunPolicy.publishIdPrefix() + subRunToolId + "-"
                                + UUID.randomUUID().toString().substring(0, 8)).build()));
    }

    private void onReasoningDelta(LLMEvent.ReasoningDelta delta, AtomicReference<String> reasoningRef,
                                  SubRunExecutionContext ctx, Long subAgentRunId, long subRunToolId,
                                  String displayName) {
        boolean firstDelta = reasoningRef.get().isBlank();
        reasoningRef.updateAndGet(c -> c + delta.text());
        // Add a sentinel line on the first frame so the frontend starts a new sub-run segment; later frames are appended as-is.
        String responseText = delta.text();
        if (firstDelta) {
            String name = displayName != null && !displayName.isBlank()
                    ? displayName : String.valueOf(subRunToolId);
            responseText = subRunPolicy.markerPrefix() + subRunToolId + ": " + name + "\n" + responseText;
        }
        RuntimeEventDataVO data = RuntimeEventDataVO.builder()
                .sessionId(ctx.getSessionId())
                .runId(ctx.getRunId())
                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                .publishId(UUID.randomUUID().toString())
                .subAgentRunId(subAgentRunId)
                .parentToolCallId(ctx.getParentToolCallId())
                // Tag sub-agent thinking with the sub-run marker throughout: the frontend routes it to the sub-card by nodeName.
                .nodeName(subRunPolicy.nodeNamePrefix() + subRunToolId)
                // First-frame marker: the frontend starts a new LLM turn from it (no need to parse a sentinel prefix).
                .firstFrame(firstDelta)
                .response(responseText).build();
        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN, data));
    }

    private void onToolCall(LLMEvent.ToolCall toolCall, List<TurnToolCall> toolCalls,
                            SubRunExecutionContext ctx, List<RuntimeTool> subTools, Long subAgentRunId,
                            long subRunToolId) {
        toolCalls.add(new TurnToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()));
        eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.PENDING,
                RuntimeEventDataVO.builder()
                        .sessionId(ctx.getSessionId())
                        .runId(ctx.getRunId())
                        .toolName(toolCall.name())
                        .displayNameCn(toolExecutor.resolveDisplayName(toolCall.name(), subTools))
                        .displayNameEn(toolExecutor.resolveDisplayNameEn(toolCall.name(), subTools))
                        .argumentsJson(toolCall.arguments())
                        .subAgentRunId(subAgentRunId)
                        .parentToolCallId(ctx.getParentToolCallId())
                        .publishId(subRunPolicy.publishIdPrefix() + subRunToolId + "-" + toolCall.id()).build()));
    }

    /** Waits for one LLM turn; returns true when cancellation was observed, throws on timeout. */
    private boolean awaitTurn(CompletableFuture<Void> future, CountDownLatch done,
                              SubRunExecutionContext ctx, long turnTimeout) {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeout);
            while (!done.await(1, TimeUnit.SECONDS)) {
                if (cancelled(ctx) || Thread.interrupted()) {
                    future.cancel(true);
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    future.cancel(true);
                    throw new RuntimeException("Sub Agent turn timed out after " + turnTimeout + "s");
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private String buildSystemPrompt(SubRunExecutionContext ctx, List<RuntimeTool> subTools, RuntimeTool tool) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getKernelContext() != null && ctx.getKernelContext().getAgentInstance() != null
                && ctx.getKernelContext().getAgentInstance().getSystemPrompt() != null) {
            sb.append(ctx.getKernelContext().getAgentInstance().getSystemPrompt());
        }
        String policySystem = subRunPolicy.systemPrompt(tool);
        if (policySystem != null && !policySystem.isBlank()) {
            sb.append("\n\n").append(policySystem);
        }
        sb.append("\n\nAvailable tools:\n");
        if (subTools.isEmpty()) {
            sb.append("(This task may not call any tools.)\n");
        } else {
            for (RuntimeTool t : subTools) {
                sb.append("- ").append(t.getLlmToolName());
                if (t.getDescription() != null && !t.getDescription().isBlank()) {
                    sb.append(": ").append(t.getDescription());
                }
                sb.append("\n");
            }
        }
        sb.append("\nExecute the task directly and return the final result; do not ask the user questions and do not call tools that are not listed.");
        return sb.toString();
    }

    private ChatMessageDTO assistantToolCall(TurnToolCall tc) {
        return assistantToolCallWithReasoning(tc, null);
    }

    private ChatMessageDTO assistantToolCallWithReasoning(TurnToolCall tc, String reasoning) {
        ChatMessageDTO msg = new ChatMessageDTO().setRole(LlmRoles.ASSISTANT).setToolCalls(List.of(
                ToolCallDTO.builder()
                        .id(tc.id())
                        .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                        .function(FunctionDefinitionDTO.builder()
                                .name(tc.name())
                                .arguments(ToolCallReason.strip(tc.arguments()))
                                .build())
                        .build()));
        // DeepSeek thinking mode requires the previous turn's reasoning_content to be sent back verbatim, otherwise 400
        if (reasoning != null && !reasoning.isBlank()) {
            msg.setReasoningContent(reasoning);
        }
        return msg;
    }

    private boolean cancelled(SubRunExecutionContext ctx) {
        Long runId = ctx.getRunId();
        Long sessionId = ctx.getSessionId();
        boolean runCancelled = runId != null && redissonClient
                .getSet(DistributedRuntimeConstants.runCancelKey(runId)).contains(Boolean.TRUE);
        boolean sessionCancelled = sessionId != null && redissonClient
                .getSet(DistributedRuntimeConstants.sessionCancelKey(sessionId)).contains(Boolean.TRUE);
        return runCancelled || sessionCancelled;
    }

    private void publishReasoning(String text, long subRunToolId) {
        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                RuntimeEventDataVO.builder()
                        .response(text)
                        .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                        .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                        .publishId(subRunPolicy.publishIdPrefix() + subRunToolId + "-" + UUID.randomUUID().toString().substring(0, 8)).build()));
    }

    /** Sub-agent tool set = the parent's full tool set minus ask_clarification and wait. */
    private List<RuntimeTool> filterTools(List<RuntimeTool> sessionTools) {
        List<RuntimeTool> result = new ArrayList<>();
        if (sessionTools == null) {
            return result;
        }
        for (RuntimeTool tool : sessionTools) {
            if (tool.getToolRef() == null) {
                result.add(tool);
                continue;
            }
            // Nested sub-runs must not use ask_clarification: a sub-agent must not ask the user questions
            if (tool.getToolRef().equals(ToolRefs.builtin(ChatClarification.INTERNAL_NAME))) {
                continue;
            }
            // wait is likewise forbidden: the suspend-resume break decision exists only in the main SessionRunner loop,
            // and the sub-agent path has no such detection. If allowed, ToolExecutor would still create a pending row and return
            // {"status":"waiting"}, but nobody would claim it — turning into a silent empty wait.
            if (tool.getToolRef().equals(ToolRefs.builtin(WaitConstants.INTERNAL_NAME))) {
                continue;
            }
            // For the same reason finish is not wired in: the explicit finalization decision (COMPLETED + summary) exists only in the main SessionRunner loop,
            // so allowing it in a sub-agent would fall through to ToolExecutor's fallback branch and return a finished result nobody consumes.
            if (tool.getToolRef().equals(ToolRefs.builtin(FinishConstants.INTERNAL_NAME))) {
                continue;
            }
            result.add(tool);
        }
        return result;
    }

    private boolean containsTool(List<RuntimeTool> tools, String llmName) {
        if (tools == null) {
            return false;
        }
        for (RuntimeTool t : tools) {
            if (t.getLlmToolName() != null && t.getLlmToolName().equals(llmName)) {
                return true;
            }
        }
        return false;
    }

    private String resolveApiKey(ModelRouteFullVO route) {
        if (route.getApiKeyId() == null) {
            return "";
        }
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

    private static String normalizeStructuredOutput(String value) {
        if (value == null || value.isBlank()) {
            return RunnerConstants.FALLBACK_COMPLETE_MSG;
        }
        String trimmed = value.trim();
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            return node != null ? node.toString() : RunnerConstants.FALLBACK_COMPLETE_MSG;
        } catch (Exception ignored) {
            // Strip fenced JSON before validation so prose + JSON is reduced to a single authoritative payload.
            int fenceStart = trimmed.indexOf("```");
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceStart >= 0 && fenceEnd > fenceStart) {
                String candidate = trimmed.substring(fenceStart + 3, fenceEnd).trim();
                if (candidate.startsWith("json")) {
                    candidate = candidate.substring(4).trim();
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(candidate);
                    return node != null ? node.toString() : RunnerConstants.FALLBACK_COMPLETE_MSG;
                } catch (Exception ignored2) {
                    // fall through
                }
            }
            int lastBrace = trimmed.lastIndexOf('}');
            int firstBrace = trimmed.indexOf('{');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                String candidate = trimmed.substring(firstBrace, lastBrace + 1);
                try {
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(candidate);
                    return node != null ? node.toString() : RunnerConstants.FALLBACK_COMPLETE_MSG;
                } catch (Exception ignored3) {
                    // fall through
                }
            }
            return RunnerConstants.FALLBACK_COMPLETE_MSG;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private record TurnResult(String content, List<TurnToolCall> toolCalls, String error, boolean cancelled,
                              String reasoning) {
        static TurnResult cancelledResult() {
            return new TurnResult(null, List.of(), null, true, null);
        }

        static TurnResult errorResult(String message) {
            return new TurnResult(null, List.of(), message, false, null);
        }
    }
}
