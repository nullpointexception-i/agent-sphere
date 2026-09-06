package com.buukle.agent.runtime.kernel.skill;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.common.skill.InvalidSkillDefinition;
import com.buukle.agent.common.skill.SkillPromptRenderer;
import com.buukle.agent.common.skill.ToolRefs;
import com.buukle.agent.instance.dtvo.enums.SkillExecutionStatus;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.spi.AgentSubAgentRunSpi;
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
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.SkillExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skill 嵌套 ReAct 执行器：主循环调用 skill_&lt;id&gt; 时在会话内启动一个子 Agent，
 * 复用会话模型路由与 allowTools 白名单，最终结果作为工具结果返回主循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillReActExecutor {

    private final KernelLlmService kernelLlmService;
    private final FallbackRouteExecutor fallbackRouteExecutor;
    private final RouteListBuilder routeListBuilder;
    private final ApiKeySpi apiKeySpi;
    private final ToolExecutor toolExecutor;
    private final RunPromptBuilder runPromptBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redissonClient;
    private final AgentRuntimeProperties properties;
    private final AgentSubAgentRunSpi subAgentRunSpi;

    /** 子 Agent（skill）thinking 展示事件标记：nodeName 前缀与首帧哨兵行（前端据此区分/内嵌折叠块）。 */
    private static final String SKILL_REASON_NODE_NAME_PREFIX = "skill:";
    private static final String SKILL_REASON_MARKER_PREFIX = "▶ Skill ";

    /** ToolExecutor skill 分支入口。 */
    public String execute(RuntimeTool skillTool, String argsJson,
                          SkillExecutionContext parentCtx, List<RuntimeTool> sessionTools) {
        AgentRuntimeProperties.SkillConfig cfg = properties.getSkill();
        if (!cfg.isExecutionEnabled()) {
            return "{\"error\":\"Skill execution disabled\"}";
        }
        long skillId = skillIdOf(skillTool);
        int depth = parentCtx.getSkillDepth() + 1;
        if (depth > cfg.getMaxNestedDepth()) {
            return "{\"error\":\"Skill max nested depth exceeded (" + cfg.getMaxNestedDepth() + ")\"}";
        }
        if (parentCtx.getSkillStack().contains(skillId)) {
            return "{\"error\":\"Skill recursive call detected: skill " + skillId + "\"}";
        }

        String promptTemplate = bindingString(skillTool, ExecBindingKeys.SKILL_PROMPT_TEMPLATE);
        if (!StringUtils.hasText(promptTemplate)) {
            return "{\"error\":\"Skill definition missing promptTemplate\"}";
        }
        String effectiveArgs = effectiveArgsJson(parentCtx, argsJson);
        String rendered;
        try {
            rendered = SkillPromptRenderer.render(promptTemplate, effectiveArgs);
        } catch (InvalidSkillDefinition e) {
            return "{\"error\":\"Skill prompt render failed: " + e.getMessage() + "\"}";
        }
        if (rendered.length() > cfg.getMaxPromptChars()) {
            rendered = rendered.substring(0, cfg.getMaxPromptChars());
        }

        Set<String> effectiveAllowed = effectiveAllowedRefs(parentCtx, skillTool);
        List<RuntimeTool> subTools = filterTools(sessionTools, effectiveAllowed);

        List<Long> childStack = new ArrayList<>(parentCtx.getSkillStack());
        childStack.add(skillId);
        // 第 4 参语义为 parentToolCallId（父工具调用 id），此前误传 effectiveArgs（长 JSON）导致
        // agent_sub_agent_run.parent_tool_call_id 超长、sub-run 创建失败；effectiveArgs 仅用于 prompt 渲染。
        SkillExecutionContext childCtx = parentCtx.child(depth, childStack, effectiveAllowed,
                parentCtx.getParentToolCallId());

        publishReasoning("⚙️ 技能 " + skillTool.getDisplayName() + " 开始执行（深度 " + depth + "）…", skillId);

        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole("system").setContent(buildSystemPrompt(childCtx, subTools)));
        messages.add(new ChatMessageDTO().setRole("user").setContent(rendered));

        // 通用子 Agent 运行：进入时创建（agentType=SKILL），结束更新状态
        AgentSubAgentRunVO subRun = null;
        try {
            subRun = subAgentRunSpi.start(childCtx.getSessionId(), childCtx.getRunId(), null,
                    childCtx.getParentToolCallId(), "SKILL", ToolRefs.skill(skillId), skillTool.getDisplayName());
        } catch (Exception e) {
            log.warn("Failed to start sub-agent run", e);
        }
        Long subAgentRunId = subRun != null ? subRun.getId() : null;

        StringBuilder allContent = new StringBuilder();
        Instant deadline = Instant.now().plus(cfg.getExecutionTimeout().isZero()
                ? Duration.ofMinutes(10) : cfg.getExecutionTimeout());
        long turnTimeout = properties.getRunner().getTurnTimeout().getSeconds();

        for (int loop = 0; loop < cfg.getMaxSubLoopCount(); loop++) {
            if (cancelled(childCtx) || Thread.interrupted()) {
                finishSubAgentRun(subAgentRunId, SkillExecutionStatus.CANCELLED.name());
                return "{\"error\":\"Skill cancelled\"}";
            }
            if (Instant.now().isAfter(deadline)) {
                publishReasoning("⏱️ 技能 " + skillTool.getDisplayName() + " 超时终止", skillId);
                finishSubAgentRun(subAgentRunId, SkillExecutionStatus.TIMEOUT.name());
                return "{\"error\":\"Skill execution timeout\"}";
            }
            TurnResult turn = turn(messages, subTools, childCtx, turnTimeout, skillId,
                    skillTool.getDisplayName(), subAgentRunId);
            if (turn.cancelled()) {
                finishSubAgentRun(subAgentRunId, SkillExecutionStatus.CANCELLED.name());
                return "{\"error\":\"Skill cancelled\"}";
            }
            if (turn.error() != null) {
                publishReasoning("❌ 技能 " + skillTool.getDisplayName() + " 执行失败", skillId);
                finishSubAgentRun(subAgentRunId, SkillExecutionStatus.FAILED.name());
                return "{\"error\":\"Skill execution failed: " + turn.error() + "\"}";
            }
            if (turn.content() != null) {
                allContent.append(turn.content());
            }
            if (turn.toolCalls().isEmpty()) {
                publishReasoning("✅ 技能 " + skillTool.getDisplayName() + " 完成", skillId);
                finishSubAgentRun(subAgentRunId, SkillExecutionStatus.COMPLETED.name());
                return truncate(allContent.toString(), cfg.getMaxResultChars());
            }
            for (TurnToolCall tc : turn.toolCalls()) {
                if (!containsTool(subTools, tc.name())) {
                    messages.add(assistantToolCall(tc));
                    messages.add(new ChatMessageDTO().setRole("tool").setToolCallId(tc.id())
                            .setContent("{\"error\":\"tool not allowed by skill allowTools: " + tc.name() + "\"}"));
                    continue;
                }
                messages.add(assistantToolCallWithReasoning(tc, turn.reasoning()));
                String publishId = "skill-" + skillId + "-" + tc.id();
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                        new RuntimeEventDataVO()
                                .setSessionId(childCtx.getSessionId())
                                .setRunId(childCtx.getRunId())
                                .setToolName(tc.name())
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
                                    .setSubAgentRunId(subAgentRunId)
                                    .setErrorMessage(e.getMessage())
                                    .setPublishId(publishId)));
                }
                messages.add(new ChatMessageDTO().setRole("tool").setToolCallId(tc.id()).setContent(result));
            }
        }
        publishReasoning("⏹️ 技能 " + skillTool.getDisplayName() + " 达到子循环上限", skillId);
        finishSubAgentRun(subAgentRunId, SkillExecutionStatus.FAILED.name());
        return truncate(allContent.length() > 0 ? allContent.toString() : RunnerConstants.FALLBACK_COMPLETE_MSG,
                cfg.getMaxResultChars());
    }

    private void finishSubAgentRun(Long subAgentRunId, String status) {
        if (subAgentRunId == null) {
            return;
        }
        try {
            subAgentRunSpi.finish(subAgentRunId, status);
        } catch (Exception e) {
            log.warn("Failed to finish sub-agent run {}", subAgentRunId, e);
        }
    }

    private TurnResult turn(List<ChatMessageDTO> messages, List<RuntimeTool> subTools,
                            SkillExecutionContext ctx, long turnTimeout, long skillId, String displayName,
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
        try {
            fallbackRouteExecutor.execute(routes, (i, route) -> {
                if (cancelled(ctx)) {
                    cancelledRef.set(true);
                    throw new RuntimeException("Skill cancelled");
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
                                                    .setNodeName(SKILL_REASON_NODE_NAME_PREFIX + skillId)
                                                    .setSubAgentRunId(subAgentRunId)
                                                    .setResponse(t.text())
                                                    .setPublishId("skill-" + skillId + "-" + UUID.randomUUID().toString().substring(0, 8))));
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
                                            // 子 Agent thinking 全程打 skill 标记：前端按 nodeName 路由到子卡片
                                            .setNodeName(SKILL_REASON_NODE_NAME_PREFIX + skillId);
                                    // 首帧加哨兵行，前端据此新建一个 skill 段；次帧原样追加到当前段
                                    if (firstDelta) {
                                        String name = displayName != null && !displayName.isBlank()
                                                ? displayName : String.valueOf(skillId);
                                        data.setResponse(SKILL_REASON_MARKER_PREFIX + skillId + ": " + name + "\n" + r.text());
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
                                                    .setArgumentsJson(tc.arguments())
                                                    .setSubAgentRunId(subAgentRunId)
                                                    .setPublishId("skill-" + skillId + "-" + tc.id())));
                                }
                                case LLMEvent.Error e -> errorRef.set(e.message());
                                default -> {
                                }
                            }
                        },
                        new LlmInteractionMeta().setRunId(ctx.getRunId()).setSessionId(ctx.getSessionId())
                                .setSubAgentRunId(subAgentRunId)
                                .setInteractionType(LlmInteractionType.SKILL_EXECUTION));
                future.whenComplete((v, ex) -> done.countDown());
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeout);
                    while (!done.await(1, TimeUnit.SECONDS)) {
                        if (cancelled(ctx) || Thread.interrupted()) {
                            cancelledRef.set(true);
                            future.cancel(true);
                            throw new RuntimeException("Skill cancelled");
                        }
                        if (System.nanoTime() >= deadline) {
                            future.cancel(true);
                            throw new RuntimeException("Skill turn timed out after " + turnTimeout + "s");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                String error = errorRef.get();
                if (error != null) {
                    throw new RuntimeException("Skill LLM error: " + error);
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

    private String buildSystemPrompt(SkillExecutionContext ctx, List<RuntimeTool> subTools) {
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

    private boolean cancelled(SkillExecutionContext ctx) {
        Long runId = ctx.getRunId();
        Long sessionId = ctx.getSessionId();
        boolean runCancelled = runId != null && redissonClient
                .getSet(DistributedRuntimeConstants.runCancelKey(runId)).contains(Boolean.TRUE);
        boolean sessionCancelled = sessionId != null && redissonClient
                .getSet(DistributedRuntimeConstants.sessionCancelKey(sessionId)).contains(Boolean.TRUE);
        return runCancelled || sessionCancelled;
    }

    private void publishReasoning(String text, long skillId) {
        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                new RuntimeEventDataVO()
                        .setResponse(text)
                        .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                        .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                        .setPublishId("skill-" + skillId + "-" + UUID.randomUUID().toString().substring(0, 8))));
    }

    /**
     * 子 Agent 实际入参：LLM 调用 skill 时的 argumentsJson 常为空（schema 缺字段）
     * 或仅含少量参数。为保证 skill 按任务严格执行，把根上下文里的「本轮用户消息全文」
     * （已含【任务配置】结构化 JSON）作为 {@code input} 合并进入参：
     * - {@code {{input}}} 占位符展开为完整任务上下文，而非空对象；
     * - 其余 {@code {{path}}} 仍从 argumentsJson 解析（缺失会自动回填 [缺参数:...] 占位标记）。
     */
    private String effectiveArgsJson(SkillExecutionContext parentCtx, String argsJson) {
        KernelContext kernelContext = parentCtx.getKernelContext();
        String userMessage = kernelContext != null ? kernelContext.getUserMessage() : null;
        if (userMessage == null || userMessage.isBlank()) {
            RunVO run = kernelContext != null ? kernelContext.getRun() : null;
            if (run != null) {
                userMessage = run.getUserMessage();
            }
        }
        if (userMessage == null || userMessage.isBlank()) {
            return argsJson;
        }
        // 合并入参：保留原 argumentsJson 的顶层字段（{{path}} 兼容），另加 input 通道（{{input}} 全文展开）
        StringBuilder merged = new StringBuilder();
        merged.append('{');
        String argsBody = null;
        if (argsJson != null && !argsJson.isBlank() && !RunnerConstants.EMPTY_JSON_ARGS.equals(argsJson)) {
            String trimmed = argsJson.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                argsBody = trimmed.substring(1, trimmed.length() - 1);
            }
        }
        if (argsBody != null && !argsBody.isBlank()) {
            merged.append(argsBody.trim()).append(',');
        }
        merged.append("\"input\":\"").append(escapeJson(userMessage)).append('"');
        merged.append('}');
        return merged.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 父链交集：父无限制（null）→ 直接用本 skill allowTools；否则取交集。 */
    private Set<String> effectiveAllowedRefs(SkillExecutionContext parentCtx, RuntimeTool skillTool) {
        Set<String> own = bindingAllowTools(skillTool);
        if (parentCtx.getInheritedAllowedToolRefs() == null) {
            return own;
        }
        Set<String> result = new HashSet<>();
        for (String ref : own) {
            if (parentCtx.getInheritedAllowedToolRefs().contains(ref)) {
                result.add(ref);
            }
        }
        return result;
    }

    private Set<String> bindingAllowTools(RuntimeTool skillTool) {
        Set<String> refs = new HashSet<>();
        Object raw = skillTool.getExecBinding() != null
                ? skillTool.getExecBinding().get(ExecBindingKeys.SKILL_ALLOW_TOOLS) : null;
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    refs.add(String.valueOf(o));
                }
            }
        }
        return refs;
    }

    private List<RuntimeTool> filterTools(List<RuntimeTool> sessionTools, Set<String> allowedRefs) {
        if (sessionTools == null || allowedRefs == null || allowedRefs.isEmpty()) {
            return List.of();
        }
        List<RuntimeTool> result = new ArrayList<>();
        for (RuntimeTool tool : sessionTools) {
            // 嵌套 Skill 禁止 ask_clarification：子 Agent 不得向用户提问
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

    private long skillIdOf(RuntimeTool skillTool) {
        if (skillTool.getToolRef() != null && skillTool.getToolRef().startsWith(ToolRefs.TYPE_SKILL + ToolRefs.SEPARATOR)) {
            try {
                return Long.parseLong(skillTool.getToolRef().substring(ToolRefs.TYPE_SKILL.length() + 1));
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return skillTool.getCapabilityId() != null ? skillTool.getCapabilityId() : -1L;
    }

    private String bindingString(RuntimeTool tool, String key) {
        Object v = tool.getExecBinding() != null ? tool.getExecBinding().get(key) : null;
        return v == null ? "" : String.valueOf(v);
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
