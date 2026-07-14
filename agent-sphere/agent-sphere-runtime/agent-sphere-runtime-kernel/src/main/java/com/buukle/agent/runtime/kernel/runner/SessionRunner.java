package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.*;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.async.FiberSet;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.*;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRunner {

    private static final Duration CONTEXT_TTL = Duration.ofMinutes(30);
    private static final Set<Long> CANCELLED_RUNS = ConcurrentHashMap.newKeySet();
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
    private final ConcurrentHashMap<Long, ContextEntry> contexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> pendingRunIds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService contextSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "context-sweeper");
        t.setDaemon(true);
        return t;
    });

    public static void cancelRun(Long runId) {
        if (runId != null) CANCELLED_RUNS.add(runId);
    }

    @PostConstruct
    void startContextSweeper() {
        contextSweeper.scheduleAtFixedRate(() -> {
            Instant now = Instant.now();
            contexts.forEach((sid, entry) -> {
                if (Duration.between(entry.createdAt(), now).compareTo(CONTEXT_TTL) > 0) {
                    contexts.remove(sid);
                    log.info("Evicted stale context for session {}", sid);
                }
            });
        }, 5, 30, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopContextSweeper() {
        contextSweeper.shutdown();
    }

    public void registerContext(Long sessionId, KernelContext ctx) {
        contexts.put(sessionId, new ContextEntry(ctx, Instant.now()));
    }

    public void setPendingRun(Long sessionId, Long runId) {
        pendingRunIds.put(sessionId, runId);
    }

    private KernelContext getContext(Long sessionId) {
        ContextEntry entry = contexts.get(sessionId);
        if (entry == null) return null;
        if (Duration.between(entry.createdAt(), Instant.now()).compareTo(CONTEXT_TTL) > 0) {
            contexts.remove(sessionId);
            return null;
        }
        return entry.ctx();
    }

    public void run(Long sessionId) {
        int loopCount = 0;
        Long currentRunId = pendingRunIds.remove(sessionId);
        RunVO currentRun = currentRunId != null ? runSpi.getRun(currentRunId) : null;
        List<ChatMessageDTO> messages = new ArrayList<>();
        boolean hasToolCalls = false;
        StringBuilder allContent = new StringBuilder();

        int maxLoopCount = properties.getRunner().getMaxLoopCount();
        int compactionRetries = 0;
        loop:
        while (loopCount < maxLoopCount) {
            if (CANCELLED_RUNS.contains(currentRunId)) break;
            log.info("------******************--------run:{}-loopCount:{}", currentRunId, loopCount);
            SessionInputManager.InputMessage input = null;
            if (!hasToolCalls) {
                input = inputManager.promoteInput(sessionId);
            }
            if (input == null && !hasToolCalls) break;

            KernelContext ctx = getContext(sessionId);
            if (currentRun == null && input != null) {
                CreateRunDTO dto = new CreateRunDTO();
                dto.setSessionId(sessionId);
                dto.setType(RunEnum.TYPE_AUTO);
                dto.setUserMessage(input.text());
                currentRun = runSpi.createRun(dto);
                currentRunId = currentRun.getId();
            }

            currentRun.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(currentRun);

            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.RUNNING,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(currentRunId)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));

            if (!hasToolCalls) {
                if (messages.isEmpty()) {
                    titleService.generateIfNeeded(ctx, input.text(), sessionId);
                    String todolistText = null;
                    try {
                        AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                                sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                        if (todoRecord != null && todoRecord.getArtifact() != null) {
                            todolistText = todoRecord.getArtifact();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load todolist for session {}", sessionId, e);
                    }
                    String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx, todolistText);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
                    }
                    messages.addAll(historyLoader.load(sessionId, currentRunId));
                }
                messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent(input.text()));
            }

            List<RuntimeTool> tools = ctx != null ? ctx.getTools() : List.of();
            List<ToolDefinitionDTO> toolDefs = runPromptBuilder.buildToolDefinitions(tools);

            // 最后一轮：注入强制总结指令
            boolean isLastLoop = loopCount >= maxLoopCount - 1;
            if (isLastLoop && !messages.isEmpty() && LlmApiConstant.ROLE_SYSTEM.equals(messages.get(0).getRole())) {
                ChatMessageDTO sysMsg = messages.get(0);
                sysMsg.setContent(sysMsg.getContent()
                        + "\n\n**IMPORTANT: This is your final turn. You MUST provide a complete summary answer now. Do NOT call any more tools.**");
            }

            AtomicReference<String> turnReasoningRef = new AtomicReference<>("");
            TurnResult turn = runTurn(sessionId, currentRunId, messages, toolDefs, tools, ctx, turnReasoningRef);

            if (CANCELLED_RUNS.contains(currentRunId)) break;

            // 统一收口：根据 TurnOutcome 判定 run 状态
            if (turn.content() != null && !turn.content().isBlank()) {
                allContent.append(turn.content());
            }

            switch (turn.outcome()) {
                case COMPACTED -> {
                    if (compactionRetries++ >= 3) {
                        log.warn("Compaction retry exhausted for run {}, sending oversized context", currentRunId);
                    } else {
                        log.info("Compaction triggered, reassembling messages (attempt {})", compactionRetries);
                        String userText = input != null ? input.text() : "";
                        messages.clear();
                        titleService.generateIfNeeded(ctx, userText, sessionId);
                        String todolistText = null;
                        try {
                            AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                                    sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                            if (todoRecord != null && todoRecord.getArtifact() != null) {
                                todolistText = todoRecord.getArtifact();
                            }
                        } catch (Exception e) {
                            log.warn("Failed to load todolist for session {}", sessionId, e);
                        }
                        String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx, todolistText);
                        if (systemPrompt != null && !systemPrompt.isBlank()) {
                            messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
                        }
                        messages.addAll(historyLoader.load(sessionId, currentRunId));
                        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent(userText));
                        hasToolCalls = true;
                        continue;
                    }
                    break loop;
                }
                case ERROR -> {
                    currentRun.setStatus(RunStatus.FAILED.name());
                    runSpi.updateRun(currentRun);
                    eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                            new RuntimeEventDataVO()
                                    .setSessionId(sessionId)
                                    .setRunId(currentRunId)
                                    .setErrorMessage(turn.errorMessage())
                                    .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
                    break loop;
                }
                case COMPLETE -> {
                    String reply = allContent.length() > 0
                            ? allContent.toString()
                            : turnReasoningRef.get();
                    if (reply != null && !reply.isBlank()) {
                        currentRun.setAssistantReply(reply);
                    }
                    currentRun.setStatus(RunStatus.COMPLETED.name());
                    runSpi.updateRun(currentRun);
                    eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                            new RuntimeEventDataVO()
                                    .setSessionId(sessionId)
                                    .setRunId(currentRunId)
                                    .setAssistantReply(currentRun.getAssistantReply())
                                    .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
                    break loop;
                }
                case CANCELLED -> {
                    break loop;
                }
                default -> {
                    // TOOL_CALLS — fall through to tool execution below
                }
            }

            List<ToolCallDTO> toolCallDTOs = new ArrayList<>();
            for (TurnToolCall tc : turn.toolCalls()) {
                toolCallDTOs.add(ToolCallDTO.builder()
                        .id(tc.id())
                        .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                        .function(FunctionDefinitionDTO.builder()
                                .name(tc.name())
                                .arguments(tc.arguments())
                                .build())
                        .build());
            }

            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setToolCalls(toolCallDTOs));

            for (TurnToolCall tc : turn.toolCalls()) {
                String runPublishId = RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id();
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(currentRunId)
                                .setToolName(tc.name())
                                .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), tools))
                                .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), tools))
                                .setArgumentsJson(tc.arguments())
                                .setPublishId(runPublishId)));
            }

            FiberSet fibers = new FiberSet(
                    properties.getTool().getMaxParallel(),
                    properties.getTool().getSubmitTimeout(),
                    properties.getTool().getExecutionTimeout());
            final Long sid = sessionId;
            final Long rid = currentRunId;
            final List<RuntimeTool> tl = tools;

            // 逐个完成时立即推送 SUCCEEDED 事件，不等所有工具完成
            fibers.onEachResult(sr -> {
                TurnToolCall matching = turn.toolCalls().stream()
                        .filter(tc -> tc.id().equals(sr.callId()))
                        .findFirst().orElse(null);
                if (matching == null) return;
                String artifact = sr.error() != null
                        ? "{\"error\":\"" + sr.error() + "\"}"
                        : (sr.result() != null ? sr.result() : "");
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                        new RuntimeEventDataVO()
                                .setSessionId(sid)
                                .setRunId(rid)
                                .setToolName(matching.name())
                                .setDisplayNameCn(toolExecutor.resolveDisplayName(matching.name(), tl))
                                .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(matching.name(), tl))
                                .setArtifact(artifact)
                                .setArgumentsJson(matching.arguments())
                                .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + matching.id())));
            });

            for (int i = 0; i < turn.toolCalls().size(); i++) {
                int fi = i;
                fibers.submit(turn.toolCalls().get(fi).id(),
                        () -> toolExecutor.execute(turn.toolCalls().get(fi), sid, rid, tl));
            }
            // Check for cancellation before tool execution
            if (CANCELLED_RUNS.contains(currentRunId)) {
                fibers.close();
                break;
            }
            var results = fibers.awaitAll();

            // Detect clarification (awaiting_user) — pause run instead of looping back to LLM
            boolean awaitingUser = false;
            for (TurnToolCall tc : turn.toolCalls()) {
                String toolResult = results.getOrDefault(tc.id(), "");
                if (ChatClarification.CLARIFICATION_TOOL_NAME.equals(tc.name()) && toolResult.contains("\"status\":\"awaiting_user\"")) {
                    awaitingUser = true;
                    break;
                }
            }
            if (awaitingUser) {
                currentRun.setStatus(RunStatus.AWAITING_USER.name());
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.AWAITING_USER,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(currentRunId)
                                .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
                break;
            }

            // Detect cancellation during tool execution
            if (CANCELLED_RUNS.remove(currentRunId)) {
                fibers.close();
                for (TurnToolCall tc : turn.toolCalls()) {
                    eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                            new RuntimeEventDataVO()
                                    .setSessionId(sessionId)
                                    .setRunId(currentRunId)
                                    .setToolName(tc.name())
                                    .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), tools))
                                    .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), tools))
                                    .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id())));
                }
                break;
            }

            for (TurnToolCall tc : turn.toolCalls()) {
                String toolResult = results.getOrDefault(tc.id(),
                        RunnerConstants.JSON_ERROR_TOOL_LOST);
                String toolMessage = toolResult;
                if (tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId())) {
                    toolMessage = toolResult + "\n\n**Reminder: If you completed a task, update its status to 'completed' by calling " + InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId() + " again.**";
                }
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_TOOL)
                        .setToolCallId(tc.id())
                        .setContent(toolMessage));
                // SUCCEEDED 事件已在 onEachResult 回调中发布，此处不再重复
            }

            boolean todowriteCalled = turn.toolCalls().stream().anyMatch(tc ->
                    tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId()));
            if (todowriteCalled && !messages.isEmpty()) {
                try {
                    AgentToolCallRecordVO todoRecord = toolCallRecordSpi.getLatestBySessionAndToolName(
                            sessionId, InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
                    String freshTodolist = todoRecord != null && todoRecord.getArtifact() != null
                            ? todoRecord.getArtifact() : null;
                    String newSystemPrompt = runPromptBuilder.buildSystemPrompt(ctx, freshTodolist);
                    messages.set(0, new ChatMessageDTO()
                            .setRole(LlmApiConstant.ROLE_SYSTEM)
                            .setContent(newSystemPrompt));
                } catch (Exception e) {
                    log.warn("Failed to refresh todolist for session {}", sessionId, e);
                }
            }

            // 最后一轮工具执行后，直接结束
            if (isLastLoop) {
                String fallback = allContent.length() > 0
                        ? allContent.toString()
                        : RunnerConstants.FALLBACK_COMPLETE_MSG;
                currentRun.setAssistantReply(fallback);
                currentRun.setStatus(RunStatus.COMPLETED.name());
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(currentRunId)
                                .setAssistantReply(fallback)
                                .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
                break;
            }

            hasToolCalls = true;
            loopCount++;
        }

        // Handle cancellation after loop exits
        if (CANCELLED_RUNS.remove(currentRunId)) {
            currentRun.setStatus(RunStatus.CANCELLED.name());
            runSpi.updateRun(currentRun);
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(currentRunId)
                            .setAssistantReply(RunnerConstants.CANCEL_MSG)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
        }

        inputManager.clear(sessionId);
        contexts.remove(sessionId);
    }

    private TurnResult runTurn(Long sessionId, Long runId, List<ChatMessageDTO> messages,
                               List<ToolDefinitionDTO> toolDefs, List<RuntimeTool> tools,
                               KernelContext ctx, AtomicReference<String> turnReasoning) {
        AtomicReference<String> contentRef = new AtomicReference<>("");
        List<TurnToolCall> toolCalls = new CopyOnWriteArrayList<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        AtomicReference<String> reasoningRef = new AtomicReference<>("");
        java.util.concurrent.atomic.AtomicBoolean compactionTriggered = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        try {
            List<ModelRouteFullVO> routes = resolveRoutes(sessionId, ctx);

            fallbackRouteExecutor.execute(routes, (i, route) -> {
                if (CANCELLED_RUNS.contains(runId)) {
                    cancelled.set(true);
                    throw new RuntimeException("Run cancelled");
                }

                // ★ 压缩检查：使用实际 route（和 LLM 调用同源）
                if (compactionService.shouldCompact(messages, route)) {
                    compactionTriggered.set(true);
                    return null;
                }

                String apiKey = resolveApiKey(route);
                ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                        .setModel(route.getModelName())
                        .setStream(true)
                        .setMessages(new ArrayList<>(messages));
                if (!toolDefs.isEmpty()) {
                    request.setTools(toolDefs);
                }

                CountDownLatch streamDone = new CountDownLatch(1);
                CompletableFuture<Void> future = kernelLlmService.stream(
                        route.getCompany(), route.getBaseUrl(), apiKey, route.getModelName(),
                        request,
                        event -> {
                            switch (event) {
                                case LLMEvent.TextDelta t -> {
                                    contentRef.updateAndGet(c -> c + t.text());
                                    eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                            new RuntimeEventDataVO()
                                                    .setSessionId(sessionId).setRunId(runId).setResponse(t.text())));
                                }
                                case LLMEvent.ReasoningDelta r -> {
                                    reasoningRef.updateAndGet(c -> c + r.text());
                                    eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                            new RuntimeEventDataVO()
                                                    .setSessionId(sessionId).setRunId(runId)
                                                    .setResponse(r.text())
                                                    .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                                                    .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                                    .setPublishId(UUID.randomUUID().toString())));
                                }
                                case LLMEvent.ToolCall tc -> {
                                    String callPublishId = RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id();
                                    toolCalls.add(new TurnToolCall(tc.id(), tc.name(), tc.arguments()));
                                    eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.PENDING,
                                            new RuntimeEventDataVO()
                                                    .setSessionId(sessionId).setRunId(runId)
                                                    .setToolName(tc.name())
                                                    .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), tools))
                                                    .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), tools))
                                                    .setArgumentsJson(tc.arguments())
                                                    .setPublishId(callPublishId)));
                                }
                                case LLMEvent.Error e -> errorRef.set(e.message());
                                default -> {
                                }
                            }
                        },
                        new LlmInteractionMeta().setRunId(runId).setSessionId(sessionId)
                                .setInteractionType(LlmInteractionType.CHAT_REPLY));

                future.whenComplete((v, ex) -> streamDone.countDown());

                try {
                    long turnTimeout = properties.getRunner().getTurnTimeout().getSeconds();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeout);
                    while (!streamDone.await(1, TimeUnit.SECONDS)) {
                        if (CANCELLED_RUNS.contains(runId)) {
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

                String error = errorRef.get();
                if (error != null) {
                    eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                            new RuntimeEventDataVO()
                                    .setSessionId(sessionId).setRunId(runId)
                                    .setResponse("⚠️ " + route.getModelName() + ": " + error + "，尝试备用路由...")
                                    .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                    .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                    .setPublishId(UUID.randomUUID().toString())));
                    throw new RuntimeException("Route failed: " + error);
                }

                return null;
            });
        } catch (Exception e) {
            if (!cancelled.get()) {
                log.error("Turn execution failed for session {}", sessionId, e);
            }
        }

        // ★ 压缩触发：compact 后让 caller 重新组装
        if (compactionTriggered.get()) {
            compactionService.compact(sessionId, runId, ctx);
            return TurnResult.compacted();
        }

        turnReasoning.set(reasoningRef.get());

        if (cancelled.get()) {
            return TurnResult.cancelled(getTurnContent(contentRef), toolCalls);
        }

        String error = errorRef.get();
        if (error != null) {
            return TurnResult.error(error);
        }

        if (!toolCalls.isEmpty()) {
            return TurnResult.toolCalls(getTurnContent(contentRef), toolCalls);
        }

        return TurnResult.complete(getTurnContent(contentRef));
    }

    private static String getTurnContent(AtomicReference<String> contentRef) {
        return contentRef.get();
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

    private record ContextEntry(KernelContext ctx, Instant createdAt) {
    }
}
