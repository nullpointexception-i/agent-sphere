package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.context.TaskLoopLimitHolder;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
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
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.buukle.agent.model.dtvo.complete.FinishReason;
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
import com.buukle.agent.runtime.kernel.service.TitleService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRunner {

    private static final Duration CONTEXT_TTL = Duration.ofMinutes(30);
    /** 单轮用户消息最多注入的附件（图片）数。 */
    private static final int MAX_USER_ATTACHMENTS = 4;
    /** Redis 取消 RSet 的占位标记（RSet 用作布尔标志，contains/add 均为该值）。 */
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

    /** 浏览器截图观察注入支持（主/子 Agent 共用，共享顺序/计数）；依赖 attachmentResolver，惰性初始化。 */
    private volatile ScreenshotObservationSupport screenshotObservationSupport;

    /** run 级取消：写 Redis RSet（任意副本可调），执行副本 loop 读取并消费。 */
    public void cancelRun(Long runId) {
        if (runId != null) {
            runCancelSet(runId).add(CANCEL_MARKER);
        }
    }

    /** session 级取消：loop 内按"当前 run 所属 session 是否被停"直接终止（不依赖 runId）。 */
    public void cancelSession(Long sessionId) {
        if (sessionId != null) {
            sessionCancelSet(sessionId).add(CANCEL_MARKER);
        }
    }

    /** 清除 session 取消标志（无活动 run / 已消费时调用，避免误杀下一个新 run）。 */
    public void clearSessionCancelled(Long sessionId) {
        if (sessionId != null) {
            sessionCancelSet(sessionId).delete();
        }
    }

    /** 该 session 当前是否有正在执行的 run（owner 租约由协调器在 RUNNING 时设置、IDLE 时清除）。 */
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

    private boolean isRunCancelled(Long runId) {
        return runId != null && runCancelSet(runId).contains(CANCEL_MARKER);
    }

    /** 会话是否处于取消态（供协调器在 run 异常退出/重启决策时判断）。 */
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
        return ctxCache().get(sessionId);
    }

    private RMapCache<Long, KernelContext> ctxCache() {
        return redissonClient.getMapCache(DistributedRuntimeConstants.KEY_CTX);
    }

    public void run(Long sessionId) {
        boolean parked = false;
        try {
            parked = runInternal(sessionId);
        } finally {
            // 停车态退出（WAITING / AWAITING_USER）**不清**输入队列/steer：唤醒的恢复输入可能在
            // 「退出窗口」内刚落进 Redis，清掉会让恢复后的新循环 promoteInput 取空 → run 空转卡死。
            // 其余退出（终态/取消/无输入）仍清，避免协调器误判"还有消息"而重启主循环。
            if (!parked) {
                inputManager.clear(sessionId);
            }
            ctxCache().remove(sessionId);
        }
    }

    /** @return 本次是否以「停车态」（WAITING / AWAITING_USER）退出 —— 供 {@link #run} 决定是否清输入。 */
    private boolean runInternal(Long sessionId) {
        boolean parked = false;
        Long currentRunId = redissonClient.<Long>getBucket(
                DistributedRuntimeConstants.sessionPendingRunKey(sessionId)).getAndDelete();
        RunVO currentRun = currentRunId != null ? runSpi.getRun(currentRunId) : null;
        // 从 run 已持久化的计数起步（V82 起）。挂起恢复（澄清/等待）会重进本方法，
        // 若仍从 0 起算，maxLoopCount 只约束「单次调用」—— 模型可反复挂起无限续命。
        int loopCount = currentRun != null && currentRun.getLoopCount() != null
                ? currentRun.getLoopCount() : 0;
        List<ChatMessageDTO> messages = new ArrayList<>();
        boolean hasToolCalls = false;
        StringBuilder allContent = new StringBuilder();

        // 实例级覆盖（最高优先）：KernelContext 已携带 agentInstance，随 run 执行副本走，天然跨副本一致
        KernelContext topCtx = getContext(sessionId);
        Integer instanceLoopLimit = topCtx != null && topCtx.getAgentInstance() != null
                && topCtx.getAgentInstance().getMaxLoopCount() != null
                && topCtx.getAgentInstance().getMaxLoopCount() > 0
                ? topCtx.getAgentInstance().getMaxLoopCount() : null;

        // 上限固化（V88）：run 有多个重入入口（wait 恢复 / 澄清恢复 / 对账抢救），而 TaskLoopLimitHolder
        // 只在任务请求线程上存在 —— 续跑时重新解析会让同一个 run 的预算中途缩水（2000 → 回落 128）。
        // 已固化则直接沿用；null（首次进入 / 存量 run）才解析，随后与 loopCount 一并落库。
        Integer pinnedLoopLimit = currentRun != null ? currentRun.getLoopLimit() : null;
        LoopLimitResolver.ResolvedLoopLimit loopLimit = LoopLimitResolver.resolveMain(
                pinnedLoopLimit, instanceLoopLimit, TaskLoopLimitHolder.get(),
                properties.getRunner().getMaxLoopCount());
        int maxLoopCount = loopLimit.limit();
        LoopLimitSource loopSource = loopLimit.source();
        log.info("Run {} execution source={}, effective loop limit={}", currentRunId, loopSource, maxLoopCount);
        int compactionRetries = 0;
        // L2：进入 agentic 流程之后，「本轮没有任何工具调用」不再是完成信号。
        // toolsEverExecuted 从已持久化的 loopCount 起步 —— loopCount 只在工具轮自增，
        // 所以 >0 就意味着这个 run 真的执行过工具；挂起恢复后依然成立。
        boolean toolsEverExecuted = loopCount > 0;
        int silentTurns = 0;
        loop:
        while (loopCount < maxLoopCount) {
            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) break;
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
            // 随这次状态更新一并落库（不额外增加写）：loopCount 在本轮末尾自增，
            // 挂起 break 发生在自增之前，所以这里记的正是「本轮即将执行的序号」，
            // 恢复后从该值续跑不会重复或漏计。
            currentRun.setLoopCount(loopCount);
            // 生效上限与 loopCount 同一位置落库（不额外增加写）：预算与计数器都是 per-run 的，
            // 分母若不固化，续跑时会在调度线程上重新解析而缩水。
            currentRun.setLoopLimit(maxLoopCount);
            runSpi.updateRun(currentRun);

            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.RUNNING,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(currentRunId)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));

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
                    messages.addAll(historyLoader.load(sessionId, currentRunId, input.isClarificationResume()));
                }
                messages.add(buildUserMessage(input, attachmentResolver));
                // 当前时间追加在消息尾部（独立小段 system，不污染 messages[0] 前缀，命中缓存）
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_SYSTEM)
                        .setContent(runPromptBuilder.currentTimeText()));
                // 待办列表独立为尾部 system 段（原位替换，不触碰 index 0，保持前缀缓存稳定）
                applyTodolistSuffix(messages, todolistText);
            }

            List<RuntimeTool> tools = ctx != null ? ctx.getTools() : List.of();
            List<ToolDefinitionDTO> toolDefs = runPromptBuilder.buildToolDefinitions(tools);

            // 尾部预告块：每轮的 loop 预算 + 临近上限时的硬收口。
            // isLastLoop 仍按 n-1 判定 —— 它是 loop cap 的执行时机（见下方工具轮收口），
            // 硬收口则提前 FINAL_TURN_INSTRUCTION_LEAD 轮，两者刻意不同。
            boolean isLastLoop = loopCount >= maxLoopCount - 1;
            applyLoopBudget(messages, loopCount, maxLoopCount);
            applySilentTurnNudge(messages, silentTurns);

            AtomicReference<String> turnReasoningRef = new AtomicReference<>("");
            TurnResult turn = runTurn(sessionId, currentRunId, messages, toolDefs, tools, ctx, turnReasoningRef);

            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) break;

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
                        titleService.generateIfNeeded(ctx, userText, sessionId, currentRunId);
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
                        String systemPrompt = runPromptBuilder.buildSystemPrompt(ctx);
                        if (systemPrompt != null && !systemPrompt.isBlank()) {
                            messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent(systemPrompt));
                        }
                        messages.addAll(historyLoader.load(sessionId, currentRunId));
                        messages.add(buildUserMessage(input, attachmentResolver));
                        messages.add(new ChatMessageDTO()
                                .setRole(LlmApiConstant.ROLE_SYSTEM)
                                .setContent(runPromptBuilder.currentTimeText()));
                        applyTodolistSuffix(messages, todolistText);
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
                    // L2：agentic 流程里的沉默不是完成。模型必须以 finish 显式收口；
                    // 连续静默超过上限就判失败 —— 否则「只说了几句就放弃」会被记成成功。
                    if (toolsEverExecuted) {
                        if (silentTurns < RunnerConstants.MAX_SILENT_TURNS) {
                            silentTurns++;
                            log.warn("Run {} ended a turn without any tool call (silent turn {}/{})",
                                    currentRunId, silentTurns, RunnerConstants.MAX_SILENT_TURNS);
                            continue;
                        }
                        log.warn("Run {} kept replying without tools for {} turn(s); failing it",
                                currentRunId, silentTurns);
                        currentRun.setStatus(RunStatus.FAILED.name());
                        runSpi.updateRun(currentRun);
                        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                                new RuntimeEventDataVO()
                                        .setSessionId(sessionId)
                                        .setRunId(currentRunId)
                                        .setErrorMessage("模型连续 " + silentTurns
                                                + " 轮未调用任何工具，也未调用 finish 收口")
                                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
                        break loop;
                    }
                    // 注意：这里<b>不能</b>置 loopCapped。走到本分支说明模型在最后一轮主动交付了答案，
                    // 那是一次正常完成，不是被上限截断。旧实现在此也置 loopCapped，导致
                    // 「恰好在最后一轮收工」与「被截断出半成品」被混为一谈；任务守卫
                    // (AgentTaskServiceImpl) 据此一票否决，把这份完整产出整个丢掉
                    // ——线上 run 1575（status=empty/stopReason=POOL_EXHAUSTED，池子跑完的完整结论）
                    // 就是这样被回成 {"error":"run loop limit reached"} 的。
                    // 真截断只在下面的工具轮分支标记。
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

            // 含 delegate 工具的轮次：外层 fiber 整批执行超时要容得下子 Agent 自身预算（子 Agent 会按自己的 deadline 自终止），
            // 否则长子 Agent 会在默认 30s 被执行超时打断 → "Tool execution lost"/InterruptedException。
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

            // Check for cancellation before ANY tool submission
            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
                break;
            }

            // finish：模型显式声明任务完成 —— agentic run 的唯一合法结束方式。
            // 必须在提交任何工具之前处理：同轮其它工具不执行（避免"边收尾边产生副作用"）。
            // 它们的 PENDING 记录已由 runTurn 的 PENDING 事件创建，必须各自补一条 FAILED 关闭，
            // 否则 timeline 会永久停在 PENDING。本分支也先于 isLastLoop 的 markLoopCapped，
            // 保证「最后一轮 finish」是一次干净完成、不被标成 loop cap。
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
                            new RuntimeEventDataVO()
                                    .setSessionId(sessionId)
                                    .setRunId(currentRunId)
                                    .setToolName(other.name())
                                    .setDisplayNameCn(toolExecutor.resolveDisplayName(other.name(), tools))
                                    .setDisplayNameEn(toolExecutor.resolveDisplayNameEn(other.name(), tools))
                                    .setErrorMessage("skipped: finish was called in the same turn")
                                    .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + other.id())));
                }
                // 关闭 finish 自身的 PENDING 记录
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(currentRunId)
                                .setToolName(finishCall.name())
                                .setDisplayNameCn(FinishConstants.DISPLAY_NAME_CN)
                                .setDisplayNameEn(FinishConstants.DISPLAY_NAME)
                                .setArgumentsJson(finishCall.arguments())
                                .setArtifact(finishCall.arguments())
                                .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + finishCall.id())));
                String declaredSummary = jsonField(finishCall.arguments(), FinishConstants.JSON_SUMMARY);
                String declaredStatus = jsonField(finishCall.arguments(), FinishConstants.JSON_STATUS);
                String reply = declaredSummary != null && !declaredSummary.isBlank()
                        ? declaredSummary
                        : (allContent.length() > 0 ? allContent.toString() : turnReasoningRef.get());
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
                log.info("Run {} finished by finish tool: declaredStatus={}", currentRunId, declaredStatus);
                break loop;
            }

            for (int i = 0; i < turn.toolCalls().size(); i++) {
                int fi = i;
                fibers.submit(turn.toolCalls().get(fi).id(),
                        () -> toolExecutor.execute(turn.toolCalls().get(fi),
                                SubRunExecutionContext.root(sid, rid, ctx), tl));
            }
            var results = fibers.awaitAll(() -> isRunCancelled(rid) || isSessionCancelled(sid));

            // If cancelled during tool execution, publish FAILED events and exit.
            // 不要在此消费 CANCELLED_RUNS：终态（run=CANCELLED + 终态事件）统一由循环后的收口处理，
            // 否则 run 会一直停留在 RUNNING 且前端收不到 run_cancelled/run_failed。
            if (isRunCancelled(currentRunId) || isSessionCancelled(sessionId)) {
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

            // 挂起判定：澄清（等人）与 wait（等时间）都让本轮 break 退出而不是回喂模型。
            // 结构化解析 status，不再用 contains("\"status\":\"awaiting_user\"")：
            // 那种写法依赖序列化后的字段顺序与字面量，改一次编码方式就会静默失效，
            // 挂起不触发 → run 空转。
            RunStatus suspendStatus = null;
            for (TurnToolCall tc : turn.toolCalls()) {
                suspendStatus = SuspenseStatusResolver.resolve(tc.name(), results.getOrDefault(tc.id(), ""));
                if (suspendStatus != null) {
                    break;
                }
            }
            if (suspendStatus != null) {
                parked = true;
                currentRun.setStatus(suspendStatus.name());
                runSpi.updateRun(currentRun);
                eventPublisher.publishEvent(new RuntimeEventVO(suspendStatus,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(currentRunId)
                                .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
                break;
            }

            for (TurnToolCall tc : turn.toolCalls()) {
                String toolResult = results.getOrDefault(tc.id(),
                        RunnerConstants.JSON_ERROR_TOOL_LOST);
                String toolMessage = toolResult;
                if (tc.name().equals(InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId())) {
                    toolMessage = toolResult + "\n\n**Reminder: keep this list in sync with actual progress (update statuses by calling "
                            + InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId()
                            + " again). A fully completed list is PROGRESS ONLY — it does NOT by itself finish the task; completion follows the task instructions (and, when a skill applies, the skill's closing conditions / expected output).**";
                }
                messages.add(new ChatMessageDTO()
                        .setRole(LlmApiConstant.ROLE_TOOL)
                        .setToolCallId(tc.id())
                        .setContent(toolMessage));
                // SUCCEEDED 事件已在 onEachResult 回调中发布，此处不再重复
            }

            // 先追加全部 tool 结果消息，再统一注入截图观察，保证
            // assistant(tool_calls) → tool* 连续 → user 观察 的合法 OpenAI 序列（多 tool 批次不被打断）
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
                    // 原位更新尾部待办槽（不重写 messages[0]，保持前缀缓存命中）
                    applyTodolistSuffix(messages, freshTodolist);
                } catch (Exception e) {
                    log.warn("Failed to refresh todolist for session {}", sessionId, e);
                }
            }

            // 最后一轮工具执行后，直接结束
            if (isLastLoop) {
                markLoopCapped(currentRun, maxLoopCount);
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
            toolsEverExecuted = true;
            silentTurns = 0;
            loopCount++;
        }

        // Handle cancellation after loop exits（run 级或 session 级取消都统一收口）
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
            currentRun.setStatus(RunStatus.CANCELLED.name());
            runSpi.updateRun(currentRun);
            // 发 CANCELLED（而非 FAILED）：与 DB 状态一致，前端走 run_cancelled 分支显示“已取消”。
            // 旧实现发 FAILED，RuntimeEventListener 收到后又把 DB 覆写回 FAILED —— 取消被记成失败。
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.CANCELLED,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(currentRunId)
                            .setAssistantReply(RunnerConstants.CANCEL_MSG)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId)));
        }
        return parked;
    }

    /** 达到轮次上限强收口时标记 run（任务守卫据 loopCapped 判失败，避免"半成品"冒充完成）。 */
    private void markLoopCapped(RunVO run, int maxLoopCount) {
        if (run == null) {
            return;
        }
        run.setLoopCapped(true);
        log.warn("Run {} hit loop cap {} (loopCapped=true, task guard will fail the task)", run.getId(), maxLoopCount);
    }

    /**
     * 原位维护消息列表尾部的待办槽：以 {@link RunnerConstants#PROMPT_TODOLIST_HEADER} 定位已有槽
     * （存在 −> 原位替换；不存在 −> 在 FINAL_TURN 之前或列表末尾插入），避免中间位移破坏前缀缓存。
     * todolistText 为空时移除旧槽。messages[0] 与历史前缀保持字节不变。
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
        // 无已有槽：优先插在尾部预告块之前，保证待办是"最末可见 system"之一
        int insertAt = tailNoticeBlockStart(messages);
        messages.add(insertAt, new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_SYSTEM)
                .setContent(suffix));
    }

    /**
     * 重建消息列表尾部的预告块：loop 预算预告（每轮都有）+ 硬收口（剩余 ≤
     * {@link RunnerConstants#FINAL_TURN_INSTRUCTION_LEAD} 轮时起，每轮都带）。
     *
     * <p>每轮重建而不是原位替换：工具轮会往 messages 尾部追加 assistant(tool_calls)/tool/观察消息，
     * 上一轮的预算副本会被挤进历史中间，既白烧 context 又与新文案矛盾。清除用 marker
     * 全表扫描，保证全列表恒只有一份当前轮次的预算。
     *
     * <p>硬收口从 n-{@code LEAD} 起注入而非等到最后一轮：收口只是提示、不强制，而模型常常正好
     * 在最后一轮才收工，只给 1 轮收尾太紧（详见 {@link RunnerConstants#FINAL_TURN_INSTRUCTION_LEAD}）。
     * 它是<b>持续</b>注入的 —— 一旦进入该窗口，n-2、n-1 同样携带，直到本方法每轮重建时重新判定。
     */
    /**
     * 静默追问：本轮没有工具调用时，给模型一条尾部提示（marker 定位，每轮替换而不是累积）。
     *
     * <p>只对已经进入 agentic 流程的 run 生效 —— 纯聊天实例（从未用过工具）保持「回文本即完成」。
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
     * 尾部预告块（loop 预算 / 终轮收口）的起始下标；没有则返回列表长度（即末尾）。
     * 待办槽插在它前面，避免轮中刷新待办后被预告挤到中间、失去「最末可见 system」的效力。
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
     * P5：技能执行一致性（**仅日志**，不写 remark/stopReason）。
     *
     * <p>判据（宽松、可计算、不依赖模型自述）：
     * <ul>
     *   <li>{@code skillReads=0} → 整个 run 从未读取技能（最强信号）；</li>
     *   <li>{@code writesBeforeSkillRead>0} → 在读取技能之前就已产生写动作（未按技能执行）。</li>
     * </ul>
     * 输出固定标记 {@code [SKILL_COMPLIANCE]}，事后可 grep / 聚合。
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

    /** chrome 写动作判定（按 argumentsJson 的 action 字段；与 chrome 工具的 ACTION_* 对齐）。 */
    private static boolean isChromeWriteAction(String args) {
        return args.contains("\"action\":\"click\"")
                || args.contains("\"action\":\"type\"")
                || args.contains("\"action\":\"key\"")
                || args.contains("\"action\":\"hover\"")
                || args.contains("\"action\":\"clickAt\"");
    }

    private TurnResult runTurn(Long sessionId, Long runId, List<ChatMessageDTO> messages,
                               List<ToolDefinitionDTO> toolDefs, List<RuntimeTool> tools,
                               KernelContext ctx, AtomicReference<String> turnReasoning) {
        int turnRetries = properties.getRunner().getLlmTurnMaxRetries();
        AtomicReference<String> turnReasoningOut = turnReasoning;
        for (int attempt = 0; attempt <= turnRetries; attempt++) {
            if (attempt > 0) {
                long backoffMs = llmTurnRetryBackoffMs(attempt);
                log.warn("LLM turn failed transiently, retrying {}/{} in {}ms: session={}",
                        attempt, turnRetries, backoffMs, sessionId);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return TurnResult.error("LLM turn retry interrupted");
                }
            }
            // 每轮独立累积，避免重试残留上轮的输出/工具调用
            AtomicReference<String> contentRef = new AtomicReference<>("");
            List<TurnToolCall> toolCalls = new CopyOnWriteArrayList<>();
            AtomicReference<String> errorRef = new AtomicReference<>();
            // 本次尝试的错误是否值得重试：4xx（408/429 除外）是终态错误，重试只会把一次失败放大成多次
            java.util.concurrent.atomic.AtomicBoolean errorRetryableRef =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicReference<String> reasoningRef = new AtomicReference<>("");
            // 本次尝试的真实 finish_reason（stop/length/content_filter/tool_calls），供调用方区分
            // 「模型主动停」与「被截断/被过滤」。
            AtomicReference<String> finishReasonRef = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean compactionTriggered = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                List<ModelRouteFullVO> routes = resolveRoutes(sessionId, ctx);

                // 图片附件请求：仅允许能处理附件的路由，避免落到纯文本模型（400/静默忽略）
                if (hasAttachmentImage(messages) && !routes.isEmpty()) {
                    List<ModelRouteFullVO> capable = routes.stream()
                            .filter(r -> Boolean.TRUE.equals(r.getSupportsAttachment()))
                            .toList();
                    if (capable.isEmpty()) {
                        // 没有能处理图片的路由：剥离图片降级为纯文本继续（不阻断 run）
                        log.info("No attachment-capable route, degrading to text: session={}, run={}", sessionId, runId);
                        stripImageParts(messages);
                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                                new RuntimeEventDataVO()
                                        .setSessionId(sessionId).setRunId(runId)
                                        .setResponse("⚠️ 当前路由均不支持图片，已按纯文本继续处理")
                                        .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                        .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                        .setPublishId(UUID.randomUUID().toString())));
                    } else {
                        routes = capable;
                    }
                }

                fallbackRouteExecutor.execute(routes, (i, route) -> {
                    if (isRunCancelled(runId) || isSessionCancelled(sessionId)) {
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
                    // 采样参数接线：全局系统配置兜底 + 实例级 config 覆盖；并行工具行为保持默认开启（与现有多工具批一致）
                    String instanceConfig = ctx != null && ctx.getAgentInstance() != null
                            ? ctx.getAgentInstance().getConfig() : null;
                    llmRequestConfigurer.apply(request, instanceConfig);
                    if (request.getParallelToolCalls() == null) {
                        request.setParallelToolCalls(true);
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
                                        // finish 的 summary 就是最终答案：正文为空时作为助手正文实时推给前端。
                                        // 否则前端只剩一张 finish 工具卡、答案空白；正文已流式输出时不重复发。
                                        if (FinishConstants.TOOL_NAME.equals(tc.name())) {
                                            String summary = jsonField(tc.arguments(), FinishConstants.JSON_SUMMARY);
                                            if (summary != null && !summary.isBlank() && contentRef.get().isEmpty()) {
                                                contentRef.set(summary);
                                                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                                        new RuntimeEventDataVO()
                                                                .setSessionId(sessionId).setRunId(runId)
                                                                .setResponse(summary)));
                                            }
                                        }
                                    }
                                    case LLMEvent.Finish f -> finishReasonRef.set(f.reason());
                                    case LLMEvent.Error e -> {
                                        errorRef.set(e.message());
                                        errorRetryableRef.set(e.retryable());
                                    }
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

            if (cancelled.get()) {
                turnReasoningOut.set(reasoningRef.get());
                return TurnResult.cancelled(getTurnContent(contentRef), toolCalls);
            }

            String error = errorRef.get();
            // 瞬时失败且尚无任何输出（未下发分片）→ 同轮限界重试；
            // 带部分输出、或上游明确给出终态错误（4xx）则不重试。
            if (error != null && errorRetryableRef.get() && attempt < turnRetries
                    && getTurnContent(contentRef).isEmpty() && toolCalls.isEmpty()) {
                continue;
            }
            turnReasoningOut.set(reasoningRef.get());
            String finishReason = finishReasonRef.get();
            if (error != null) {
                return TurnResult.error(error).withFinishReason(finishReason);
            }
            if (!toolCalls.isEmpty()) {
                // 工具轮：即使正文被截断，工具参数已完整或本就不可用，按正常工具轮继续
                return TurnResult.toolCalls(getTurnContent(contentRef), toolCalls).withFinishReason(finishReason);
            }
            // 没有工具调用却「非正常结束」→ 不能当完成（否则半截答案会被标 COMPLETED）
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
        turnReasoningOut.set("");
        return TurnResult.error("LLM turn retries exhausted");
    }

    /** 读取工具入参里的字符串字段；缺失/非法一律返回 null（收口逻辑要能容忍脏入参）。 */
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
     * 第 {@code attempt} 次重试前的等待时长：{@code 基数 × 2^(attempt-1)}，封顶 {@code MAX}。
     *
     * <p>1-based attempt：第一次重试等 {@code BASE}，之后 2×/4× 递增。
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
     * 构建 user 消息：无附件为纯文本，有附件按 OpenAI 兼容 content parts（文本+图片）注入。
     * 附件仅携带 fileKey，data URL 在组消息时才经 {@code attachmentResolver} 惰性回读。
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

    /** 当前轮是否含图片附件：末条 USER 消息 content 为 parts 且含 image_url。 */
    static boolean hasAttachmentImage(List<ChatMessageDTO> messages) {
        return ScreenshotObservationSupport.hasAttachmentImage(messages);
    }

    /**
     * 无支持图片的路由时降级：把最后一条 USER 消息的 parts 折叠为纯文本 String
     * （拼接 text parts，空则用 "[图片]" 占位，避免纯图片消息变成空 content 发往模型）。
     */
    static void stripImageParts(List<ChatMessageDTO> messages) {
        ScreenshotObservationSupport.stripImageParts(messages);
    }

    /**
     * 浏览器截图工具结果含 data.screenshot → 追加一条 USER 观察消息（text + image_url dataURL）。
     * 委托 {@link ScreenshotObservationSupport}（与子 Agent 共用逻辑与次数上限）。
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
}
