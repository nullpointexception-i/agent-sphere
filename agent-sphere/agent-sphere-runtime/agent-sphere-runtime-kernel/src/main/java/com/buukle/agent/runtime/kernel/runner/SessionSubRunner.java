package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.enums.SubRunStatus;
import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.spi.AgentSubAgentRunSpi;
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
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
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
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import com.buukle.agent.runtime.kernel.util.ScreenshotObservationSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SubRun 嵌套 ReAct 执行器（通用）：主循环调用某一 sub-run 工具 时在会话内
 * 启动一个子 Agent，复用会话模型路由与策略（{@link SubRunPolicy}）给出的预算/白名单/命名，
 * 最终结果作为工具结果返回主循环。策略决定宿主差异，本类不做任何宿主耦合。
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
    /** 可选：统一 Timeline 索引（无则跳过子 Agent 头行，不影响功能）。 */
    private final ObjectProvider<AgentTimelineSpi> timelineSpiProvider;
    private final ChatAttachmentResolver attachmentResolver;
    /** 浏览器截图观察注入支持（主/子 Agent 共用，共享顺序/计数）；依赖 attachmentResolver，惰性初始化。 */
    private volatile ScreenshotObservationSupport screenshotObservationSupport;

    private ScreenshotObservationSupport screenshotSupport() {
        ScreenshotObservationSupport support = screenshotObservationSupport;
        if (support == null) {
            support = new ScreenshotObservationSupport(properties.getRunner().getMaxScreenshotsPerRun(), attachmentResolver);
            screenshotObservationSupport = support;
        }
        return support;
    }

    /** ToolExecutor sub-run 分支入口。 */
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

        if (!policy.hasPromptTemplate(subRunTool)) {
            return policy.errorMissingPrompt();
        }
        String rendered;
        try {
            rendered = policy.renderPrompt(subRunTool, argsJson, parentCtx);
        } catch (InvalidSubRunDefinition e) {
            return policy.errorRenderFailed(e.getMessage());
        }

        Set<String> effectiveAllowed = policy.allowedRefs(subRunTool, parentCtx);
        List<RuntimeTool> subTools = filterTools(sessionTools, effectiveAllowed);

        publishReasoning(policy.textStarted(displayName, prep.depth()), subRunToolId);

        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole("system").setContent(buildSystemPrompt(childCtx, subTools)));
        messages.add(new ChatMessageDTO().setRole("user").setContent(rendered));

        // 通用子 Agent 运行：进入时创建（agentType 由策略给出），结束更新状态
        AgentSubAgentRunVO subRun = null;
        try {
            subRun = subAgentRunSpi.start(childCtx.getSessionId(), childCtx.getRunId(), null,
                    childCtx.getParentToolCallId(), policy.agentType(), policy.toolRef(subRunToolId), displayName);
        } catch (Exception e) {
            log.warn("Failed to start sub-agent run", e);
        }
        Long subAgentRunId = subRun != null ? subRun.getId() : null;

        // 统一 Timeline：子 Agent 头行（正文按 agent_sub_agent_run 懒解，此处仅标记 + 封口）
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

        StringBuilder allContent = new StringBuilder();
        Duration timeout = policy.executionTimeout();
        Instant deadline = Instant.now().plus(timeout != null && !timeout.isZero()
                ? timeout : Duration.ofMinutes(10));
        long turnTimeout = properties.getRunner().getTurnTimeout().getSeconds();

        // 子 Agent 复用同一实例级循环上限（与主循环一致，最高优先）；未配置则按 skill 配置回落，不继承任务提额
        Integer instanceLoopLimit = childCtx.getKernelContext() != null
                && childCtx.getKernelContext().getAgentInstance() != null
                ? childCtx.getKernelContext().getAgentInstance().getMaxLoopCount() : null;
        LoopLimitResolver.ResolvedLoopLimit loopLimit = LoopLimitResolver.resolveSub(
                instanceLoopLimit, properties.getSkill().getMaxSubLoopCount());
        log.info("Sub-run session={} run={} source={}, effective loop limit={}",
                childCtx.getSessionId(), childCtx.getRunId(), loopLimit.source(), loopLimit.limit());

        for (int loop = 0; loop < loopLimit.limit(); loop++) {
            if (cancelled(childCtx) || Thread.interrupted()) {
                finishSubAgentRun(subAgentRunId, SubRunStatus.CANCELLED.name());
                return policy.errorCancelled();
            }
            if (Instant.now().isAfter(deadline)) {
                publishReasoning(policy.textTimeout(displayName), subRunToolId);
                finishSubAgentRun(subAgentRunId, SubRunStatus.TIMEOUT.name());
                return policy.errorTimeout();
            }
            TurnResult turn = turn(messages, subTools, childCtx, turnTimeout, subRunToolId,
                    subRunTool.getDisplayName(), subAgentRunId);
            if (turn.cancelled()) {
                finishSubAgentRun(subAgentRunId, SubRunStatus.CANCELLED.name());
                return policy.errorCancelled();
            }
            if (turn.error() != null) {
                publishReasoning(policy.textFailed(displayName), subRunToolId);
                finishSubAgentRun(subAgentRunId, SubRunStatus.FAILED.name());
                return policy.errorExecutionFailed(turn.error());
            }
            if (turn.content() != null) {
                allContent.append(turn.content());
            }
            if (turn.toolCalls().isEmpty()) {
                publishReasoning(policy.textCompleted(displayName), subRunToolId);
                finishSubAgentRun(subAgentRunId, SubRunStatus.COMPLETED.name());
                return truncate(allContent.toString(), policy.maxResultChars());
            }
            // 记录本轮每个工具结果（截图观察在全部 tool 消息追加后再统一注入，避免打断 assistant.tool_calls ↔ tool 配对）
            Map<String, String> toolResults = new java.util.HashMap<>();
            for (TurnToolCall tc : turn.toolCalls()) {
                if (!containsTool(subTools, tc.name())) {
                    messages.add(assistantToolCall(tc));
                    messages.add(new ChatMessageDTO().setRole("tool").setToolCallId(tc.id())
                            .setContent(policy.errorNotAllowed(tc.name())));
                    continue;
                }
                messages.add(assistantToolCallWithReasoning(tc, turn.reasoning()));
                String publishId = policy.publishIdPrefix() + subRunToolId + "-" + tc.id();
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                        new RuntimeEventDataVO()
                                .setSessionId(childCtx.getSessionId())
                                .setRunId(childCtx.getRunId())
                                .setToolName(tc.name())
                                .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                                .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                                .setSubAgentRunId(subAgentRunId)
                                .setArgumentsJson(tc.arguments())
                                .setPublishId(publishId)));
                String result;
                try {
                    result = toolExecutor.execute(tc, childCtx, subTools);
eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                        new RuntimeEventDataVO()
                                .setSessionId(childCtx.getSessionId())
                                .setRunId(childCtx.getRunId())
                                .setToolName(tc.name())
                                .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                                .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                                .setSubAgentRunId(subAgentRunId)
                                .setArtifact(result)
                                .setPublishId(publishId)));
                } catch (Exception e) {
                    result = "{\"error\":\"" + e.getMessage() + "\"}";
eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                        new RuntimeEventDataVO()
                                .setSessionId(childCtx.getSessionId())
                                .setRunId(childCtx.getRunId())
                                .setToolName(tc.name())
                                .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                                .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                                .setSubAgentRunId(subAgentRunId)
                                .setErrorMessage(e.getMessage())
                                .setPublishId(publishId)));
                }
                messages.add(new ChatMessageDTO().setRole("tool").setToolCallId(tc.id()).setContent(result));
                toolResults.put(tc.id(), result);
            }
            // 先追加全部 tool 结果消息，再统一注入截图观察，保证 tool 消息紧贴各自 assistant.tool_calls 且不互相打断
            for (TurnToolCall tc : turn.toolCalls()) {
                screenshotSupport().injectScreenshotObservation(childCtx.getSessionId(), subAgentRunId, messages,
                        toolResults.getOrDefault(tc.id(), ""));
            }
        }
        publishReasoning(policy.textSubLoopCapped(displayName), subRunToolId);
        finishSubAgentRun(subAgentRunId, SubRunStatus.FAILED.name());
        return truncate(allContent.length() > 0 ? allContent.toString() : RunnerConstants.FALLBACK_COMPLETE_MSG,
                policy.maxResultChars());
    }

    private void finishSubAgentRun(Long subAgentRunId, String status) {
        if (subAgentRunId == null) {
            return;
        }
        try {
            subAgentRunSpi.finish(subAgentRunId, status);
            AgentTimelineSpi ts = timelineSpiProvider.getIfAvailable();
            if (ts != null) {
                String state = switch (status) {
                    case "COMPLETED" -> TimelineState.COMPLETED.getCode();
                    case "TIMEOUT" -> TimelineState.TIMEOUT.getCode();
                    case "CANCELLED" -> TimelineState.CANCELLED.getCode();
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
        List<ModelRouteFullVO> routes = routeListBuilder.fromContext(ctx.getKernelContext());
        if (routes.isEmpty() && ctx.getKernelContext() != null
                && ctx.getKernelContext().getAgentInstance() != null) {
            routes = routeListBuilder.fromInstance(ctx.getKernelContext().getAgentInstance().getId());
        }
        if (routes.isEmpty()) {
            return TurnResult.errorResult("no model route available");
        }
        // 图片附件/截图观察：无支持图片的路由 → 剥离图片降级为纯文本继续（与主 Agent 一致，不阻断）
        if (ScreenshotObservationSupport.hasAttachmentImage(messages)) {
            boolean anyCapable = false;
            for (ModelRouteFullVO r : routes) {
                if (Boolean.TRUE.equals(r.getSupportsAttachment())) {
                    anyCapable = true;
                    break;
                }
            }
            if (!anyCapable) {
                log.info("No attachment-capable sub-route, degrading to text: session={}, sub={}",
                        ctx.getSessionId(), subAgentRunId);
                ScreenshotObservationSupport.stripImageParts(messages);
                eventPublisher.publishEvent(new RuntimeEventVO(
                        FlowEventType.REASONING_TOKEN,
                        new RuntimeEventDataVO()
                                .setSessionId(ctx.getSessionId())
                                .setRunId(ctx.getRunId())
                                .setSubAgentRunId(subAgentRunId)
                                .setResponse("⚠️ 当前路由均不支持图片，已按纯文本继续处理")
                                .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                .setPublishId(UUID.randomUUID().toString())));
            }
        }
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
                CountDownLatch done = new CountDownLatch(1);
                CompletableFuture<Void> future = kernelLlmService.stream(
                        route.getCompany(), route.getBaseUrl(), apiKey, route.getModelName(), request,
                        event -> {
                            switch (event) {
                                case LLMEvent.TextDelta t -> {
                                    contentRef.updateAndGet(c -> c + t.text());
                                    // 子 Agent model reply 实时流式发布（供前端子卡片展示回复）
                                    eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                            new RuntimeEventDataVO()
                                                    .setSessionId(ctx.getSessionId())
                                                    .setRunId(ctx.getRunId())
                                                    .setNodeName(subRunPolicy.nodeNamePrefix() + subRunToolId)
                                                    .setSubAgentRunId(subAgentRunId)
                                                    .setResponse(t.text())
                                                    .setPublishId(subRunPolicy.publishIdPrefix() + subRunToolId + "-" + UUID.randomUUID().toString().substring(0, 8))));
                                }
                                case LLMEvent.ReasoningDelta r -> {
                                    boolean firstDelta = reasoningRef.get().isBlank();
                                    reasoningRef.updateAndGet(c -> c + r.text());
                                    RuntimeEventDataVO data = new RuntimeEventDataVO()
                                            .setSessionId(ctx.getSessionId())
                                            .setRunId(ctx.getRunId())
                                            .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                                            .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                            .setPublishId(UUID.randomUUID().toString())
                                            .setSubAgentRunId(subAgentRunId)
                                            // 子 Agent thinking 全程打 sub-run 标记：前端按 nodeName 路由到子卡片
                                            .setNodeName(subRunPolicy.nodeNamePrefix() + subRunToolId)
                                            // 首帧标记：前端据此刻新 LLM 轮（避免解析哨兵前缀）
                                            .setFirstFrame(firstDelta);
                                    // 首帧加哨兵行，前端据此新建一个 sub-run 段；次帧原样追加到当前段
                                    if (firstDelta) {
                                        String name = displayName != null && !displayName.isBlank()
                                                ? displayName : String.valueOf(subRunToolId);
                                        data.setResponse(subRunPolicy.markerPrefix() + subRunToolId + ": " + name + "\n" + r.text());
                                    } else {
                                        data.setResponse(r.text());
                                    }
                                    eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN, data));
                                }
                                case LLMEvent.ToolCall tc -> {
                                    toolCalls.add(new TurnToolCall(tc.id(), tc.name(), tc.arguments()));
                                    eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.PENDING,
                                            new RuntimeEventDataVO()
                                                    .setSessionId(ctx.getSessionId())
                                                    .setRunId(ctx.getRunId())
                                                    .setToolName(tc.name())
                                                    .setDisplayNameCn(toolExecutor.resolveDisplayName(tc.name(), subTools))
                                                    .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), subTools))
                                                    .setArgumentsJson(tc.arguments())
                                                    .setSubAgentRunId(subAgentRunId)
                                                    .setPublishId(subRunPolicy.publishIdPrefix() + subRunToolId + "-" + tc.id())));
                                }
                                case LLMEvent.Error e -> errorRef.set(e.message());
                                default -> {
                                }
                            }
                        },
                        new LlmInteractionMeta().setRunId(ctx.getRunId()).setSessionId(ctx.getSessionId())
                                .setSubAgentRunId(subAgentRunId)
                                .setInteractionType(subRunPolicy.interactionType()));
                future.whenComplete((v, ex) -> done.countDown());
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeout);
                    while (!done.await(1, TimeUnit.SECONDS)) {
                        if (cancelled(ctx) || Thread.interrupted()) {
                            cancelledRef.set(true);
                            future.cancel(true);
                            throw new RuntimeException("Sub Agent cancelled");
                        }
                        if (System.nanoTime() >= deadline) {
                            future.cancel(true);
                            throw new RuntimeException("Sub Agent turn timed out after " + turnTimeout + "s");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
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

    private String buildSystemPrompt(SubRunExecutionContext ctx, List<RuntimeTool> subTools) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getKernelContext() != null && ctx.getKernelContext().getAgentInstance() != null
                && ctx.getKernelContext().getAgentInstance().getSystemPrompt() != null) {
            sb.append(ctx.getKernelContext().getAgentInstance().getSystemPrompt());
        }
        sb.append("\n\n允许使用的工具：\n");
        if (subTools.isEmpty()) {
            sb.append("（本任务不允许调用任何工具）\n");
        } else {
            for (RuntimeTool t : subTools) {
                sb.append("- ").append(t.getLlmToolName());
                if (t.getDescription() != null && !t.getDescription().isBlank()) {
                    sb.append(": ").append(t.getDescription());
                }
                sb.append("\n");
            }
        }
        sb.append("\n请直接执行任务并返回最终结果；不要向用户提问，不要调用未列出的工具。");
        return sb.toString();
    }

    private ChatMessageDTO assistantToolCall(TurnToolCall tc) {
        return assistantToolCallWithReasoning(tc, null);
    }

    private ChatMessageDTO assistantToolCallWithReasoning(TurnToolCall tc, String reasoning) {
        ChatMessageDTO msg = new ChatMessageDTO().setRole("assistant").setToolCalls(List.of(
                ToolCallDTO.builder()
                        .id(tc.id())
                        .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                        .function(FunctionDefinitionDTO.builder()
                                .name(tc.name())
                                .arguments(tc.arguments())
                                .build())
                        .build()));
        // DeepSeek thinking 模式要求把上一轮的 reasoning_content 原样回传，否则 400
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
                new RuntimeEventDataVO()
                        .setResponse(text)
                        .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                        .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                        .setPublishId(subRunPolicy.publishIdPrefix() + subRunToolId + "-" + UUID.randomUUID().toString().substring(0, 8))));
    }

    private List<RuntimeTool> filterTools(List<RuntimeTool> sessionTools, Set<String> allowedRefs) {
        if (sessionTools == null || allowedRefs == null || allowedRefs.isEmpty()) {
            return List.of();
        }
        List<RuntimeTool> result = new ArrayList<>();
        for (RuntimeTool tool : sessionTools) {
            // 嵌套 Sub run 禁止 ask_clarification：子 Agent 不得向用户提问
            if (tool.getToolRef() != null
                    && tool.getToolRef().equals(ToolRefs.builtin(ChatClarification.INTERNAL_NAME))) {
                continue;
            }
            if (matchesAny(allowedRefs, tool)) {
                result.add(tool);
            }
        }
        return result;
    }

    private boolean matchesAny(Set<String> allowedRefs, RuntimeTool tool) {
        for (String ref : allowedRefs) {
            String r = ref.trim();
            if (ToolRefs.WILDCARD.equals(r)) {
                return true;
            }
            if (tool.getToolRef() != null && r.equalsIgnoreCase(tool.getToolRef())) {
                return true;
            }
            if (tool.getLlmToolName() != null && r.equalsIgnoreCase(tool.getLlmToolName())) {
                return true;
            }
        }
        return false;
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
