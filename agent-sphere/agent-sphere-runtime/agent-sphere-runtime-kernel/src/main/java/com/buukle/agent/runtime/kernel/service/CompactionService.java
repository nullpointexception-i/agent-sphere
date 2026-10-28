package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.CompactionStatus;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.prompt.CompactionPromptConstant;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompactionService {

    public static final String COMPACTED_SENTINEL = "__COMPACTED__";

    private static final double INPUT_RATIO = 0.5;
    private static final double OUTPUT_RATIO = 2.0 / 3;
    /** 估算安全系数：中文/JSON 的字符密度波动大，留 10% 余量避免「估算没到阈值、实际已超上下文」。 */
    private static final double ESTIMATE_SAFETY_RATIO = 1.1;
    /** 工具 schema / 纯 JSON 以 ASCII 为主，字符/token 明显高于中文正文；用 1.8 会严重高估。 */
    private static final double JSON_TOKEN_ESTIMATE_DIVISOR = 3.5;
    /** 单张图片的保守 token 成本（不解析 base64；按常见多模态路由的高位估）。 */
    private static final long IMAGE_PART_TOKENS = 1000L;
    /** 输入预算中为输出预留的最低余量（token）。 */
    private static final long MIN_OUTPUT_MARGIN_TOKENS = 512L;
    /** 输入预算中为输出预留的余量比例（占 max_input）。 */
    private static final double OUTPUT_MARGIN_RATIO = 0.05;
    /** 预算下限（占 max_input）：即使输出额度配置异常也不至于把预算压到无法工作。 */
    private static final double MIN_INPUT_BUDGET_RATIO = 0.25;
    /** 摘要硬上限：防止长期运行中摘要逐轮膨胀。 */
    private static final long SUMMARY_MAX_TOKENS_CAP = 4000L;
    private static final long SUMMARY_MIN_TOKENS = 256L;
    /** 单个 run 的用户/助手正文在压缩输入中的最大字符数（超出标记省略）。 */
    private static final int COMPACTION_FIELD_MAX_CHARS = 2000;
    /** 单个 run 的工具参数在压缩输入中的最大字符数。 */
    private static final int COMPACTION_ARGS_MAX_CHARS = 500;

    private final AgentRuntimeProperties agentRuntimeProperties;
    private final RunSpi runSpi;
    private final SessionSpi sessionSpi;
    private final KernelLlmService kernelLlmService;
    private final ApiKeySpi apiKeySpi;
    private final AgentCompactRecordSpi compactRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final RouteListBuilder routeListBuilder;
    private final FallbackRouteExecutor fallbackRouteExecutor;
    private final LlmRequestConfigurer llmRequestConfigurer;
    /** 只对每个缺 max_input_tokens 的路由告警一次，避免每轮刷日志。 */
    private final Set<Long> warnedMissingMaxInput = ConcurrentHashMap.newKeySet();
    /** 只对每个「压缩不可能把上下文压进预算」的路由告警一次，避免每轮刷日志。 */
    private final Set<Long> warnedInfeasibleCompaction = ConcurrentHashMap.newKeySet();

    /**
     * 是否需要压缩。
     *
     * <p><b>必须把工具 schema 计入估算</b>：请求体 = messages + tools + 输出；10 个工具的 JSON 约 1 万
     * token，只算 messages 会让「估算没到阈值、实际已超上下文」——线上 32k 路由（工具 11.7k token）
     * 就是这样在 32,852 实际 token 时报 400 的。
     *
     * <p>再加 10% 安全系数（字符/1.8 ×1.1）。route 的 {@code max_output_tokens}
     * 不参与请求（请求的 max_tokens 由 sampling config 控制），这里也不从总上下文里扣它；
     * 0.7 的 budgetRatio 本身已为输出预留 30%。
     *
     * <p><b>固定开销本身就塞不进模型时才跳过压缩</b>：主 system prompt 与工具 schema 是每轮都带的，
     * 压缩只能压历史。若 {@code leading system + tools} 已超过 {@code max_input_tokens}（硬上限，不再乘
     * 安全系数），压缩就是不可能的，只会每轮白烧 3 次压缩调用（线上 route 4 = 20k 的测试路由）。
     * 此时返回 false，让请求带着超上下文直接发：模型接受就继续，拒绝则走 400 终态失败。
     * 注意门槛必须是硬上限而不是 0.7×budget —— 后者会让「工具占大头但真实仍可容纳」的路由
     * （如 32k 路由）被永久关闭压缩，历史继续涨到真溢出。
     */
    public boolean shouldCompact(List<ChatMessageDTO> messages, ModelRouteFullVO route,
                                 List<ToolDefinitionDTO> tools, Integer effectiveMaxOutputTokens) {
        if (route == null) return false;
        Long routeId = route.getId();
        long maxInputTokens = resolveMaxInputTokens(route);
        long budget = resolveInputBudget(maxInputTokens, effectiveMaxOutputTokens);
        long toolTokens = estimateToolTokens(tools);
        long fixedTokens = estimateFixedTokens(messages, toolTokens);
        if (fixedTokens >= maxInputTokens) {
            if (routeId == null || warnedInfeasibleCompaction.add(routeId)) {
                log.warn("Route {} cannot compact: fixed(leading system + tools)={} >= maxInput={}; "
                        + "sending oversized context instead", routeId, fixedTokens, maxInputTokens);
            }
            return false;
        }
        long messageTokens = estimateMessagesTokens(messages);
        long estimated = (long) Math.ceil((messageTokens + toolTokens) * ESTIMATE_SAFETY_RATIO);
        log.debug("Compaction check: routeId={}, messageTokens={}, toolTokens={}, fixed={}, estimated={}, budget={}, maxInput={}, outputCap={}",
                routeId, messageTokens, toolTokens, fixedTokens, estimated, budget, maxInputTokens,
                effectiveMaxOutputTokens);
        return estimated > budget;
    }

    private long resolveMaxInputTokens(ModelRouteFullVO route) {
        if (route.getMaxInputTokens() != null) {
            return route.getMaxInputTokens();
        }
        Long routeId = route.getId();
        if (routeId == null || warnedMissingMaxInput.add(routeId)) {
            log.warn("Route {} has no max_input_tokens; compaction falls back to {} tokens and may never trigger",
                    routeId, RunnerConstants.DEFAULT_MAX_INPUT_TOKENS);
        }
        return RunnerConstants.DEFAULT_MAX_INPUT_TOKENS;
    }

    /**
     * 输入预算：按最终请求实际可用的输入空间计算。
     *
     * <p>输出额度来自合并后的 sampling config（实例优先）。有额度时预算 =
     * {@code max_input - output_cap - margin}，而不是固定的 0.7 比例 —— 后者在输出额度很小或
     * 很大时都会误判。没有输出额度时才回落到 {@code budget-ratio}。预算下限为
     * {@code max_input × 0.25}，避免异常配置把预算压到不可用。
     */
    private long resolveInputBudget(long maxInputTokens, Integer effectiveMaxOutputTokens) {
        long ratioBudget = (long) (maxInputTokens
                * agentRuntimeProperties.getRunner().getCompaction().getBudgetRatio());
        long floor = Math.max(1L, (long) (maxInputTokens * MIN_INPUT_BUDGET_RATIO));
        if (effectiveMaxOutputTokens == null || effectiveMaxOutputTokens <= 0) {
            return Math.max(floor, ratioBudget);
        }
        long margin = Math.max(MIN_OUTPUT_MARGIN_TOKENS,
                (long) Math.ceil(maxInputTokens * OUTPUT_MARGIN_RATIO));
        long capacity = maxInputTokens - effectiveMaxOutputTokens - margin;
        return Math.max(floor, Math.min(ratioBudget, capacity));
    }

    /**
     * 压缩无法削减的部分：开头的连续 system 消息（主提示词 + 历史层注入的摘要/取消提示）+ 工具 schema。
     * 只有 leading system 算固定开销 —— 尾部 system（时间/待办/预算）每轮重建，同样压不掉，但体量很小，
     * 这里不额外计入以免误伤边界场景。
     */
    private long estimateFixedTokens(List<ChatMessageDTO> messages, long toolTokens) {
        long systemTokens = 0;
        if (messages != null) {
            List<ChatMessageDTO> leadingSystem = new ArrayList<>();
            for (ChatMessageDTO msg : messages) {
                if (!LlmApiConstant.ROLE_SYSTEM.equals(msg.getRole())) {
                    break;
                }
                leadingSystem.add(msg);
            }
            systemTokens = estimateMessagesTokens(leadingSystem);
        }
        return systemTokens + toolTokens;
    }

    /** 中文正文的字符数 → token（向上取整；除数见 {@link RunnerConstants#TOKEN_ESTIMATE_DIVISOR}）。 */
    private static long charsToTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (long) Math.ceil(chars / RunnerConstants.TOKEN_ESTIMATE_DIVISOR);
    }

    /** 工具 schema / JSON 的字符数 → token：以 ASCII 为主，用独立除数，避免按中文密度高估。 */
    private static long jsonCharsToTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (long) Math.ceil(chars / JSON_TOKEN_ESTIMATE_DIVISOR);
    }

    /** 工具定义也是 prompt 的一部分：把 tools JSON 的字符数折算成 token。 */
    private long estimateToolTokens(List<ToolDefinitionDTO> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        String json = JsonUtils.toJson(tools);
        return json == null ? 0 : jsonCharsToTokens(json.length());
    }

    public CompactionOutcome compact(Long sessionId, Long runId, KernelContext ctx) {
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session == null) return CompactionOutcome.NO_HISTORY;
            List<ModelRouteFullVO> routes = routeListBuilder.fromInstance(session.getAgentInstanceId());
            return fallbackRouteExecutor.execute(routes,
                    (i, route) -> doCompact(sessionId, session, route, runId));
        } catch (Exception e) {
            log.warn("Compaction failed for session {}", sessionId, e);
            return CompactionOutcome.FAILED;
        }
    }

    /**
     * 压缩「游标之后的已结束历史」的最老一段。
     *
     * <p>只压缩已结束的 run：RUNNING（含当前 run）与停车态 run 不进入摘要候选 —— 它们由内存消息承载，
     * 压缩重组时直接保留，不再从数据库重放。游标只推进到**实际写入摘要的最后一个 run**。
     *
     * <p>候选按从旧到新顺序连续纳入，直到压缩输入预算用尽；若第一条 run 本身就超预算，
     * 对它做字段级/整体截断并标记省略，随后停止（不会跳过它去压缩更新的 run）。
     */
    private CompactionOutcome doCompact(Long sessionId, SessionVO session, ModelRouteFullVO route, Long runId) {
        AgentCompactRecordVO latest = compactRecordSpi.getLatestCompleted(sessionId);
        Long cursor = latest != null && latest.getCompactedUptoRunId() != null
                ? latest.getCompactedUptoRunId() : 0L;

        List<RunVO> candidates = runSpi.listRunsBySessionAfterId(sessionId, cursor).stream()
                .filter(r -> r.getId() != null && !Objects.equals(r.getId(), runId))
                .filter(r -> isCompactionEligible(r.getStatus()))
                .toList();
        if (candidates.isEmpty()) {
            return CompactionOutcome.NO_HISTORY;
        }

        long maxInputTokens = resolveMaxInputTokens(route);
        long maxOutputTokens = route.getMaxOutputTokens() != null ? route.getMaxOutputTokens()
                : RunnerConstants.DEFAULT_MAX_OUTPUT_TOKENS;

        long compactionInputLimit = (long) (maxInputTokens * INPUT_RATIO);
        CompactInput compactInput = buildCompactInput(sessionId, candidates, compactionInputLimit);
        if (compactInput.lastRunId() == null || compactInput.text().isBlank()) {
            log.warn("Compaction made no progress for session {}: candidates={}, lastRunId={}",
                    sessionId, candidates.size(), compactInput.lastRunId());
            return CompactionOutcome.NO_PROGRESS;
        }

        String detail = "合并 " + compactInput.includedRuns() + " 轮历史"
                + (compactInput.truncated() ? "（超长内容已截断）" : "");
        // publishId 必须每次压缩唯一：同一 session 的多次压缩要各自成行（timeline 按它复用/封口）。
        String publishId = RuntimeEventTypeConstant.PUBLISH_ID_COMPACT + sessionId + ":" + UUID.randomUUID();
        // 先建记录：失败也有审计行，timeline 也能立刻挂上 ref（刷新后正文从 summary_after 解析）。
        AgentCompactRecordVO record = compactRecordSpi.createRecord(sessionId);
        compactRecordSpi.updateRunning(record.getId());
        publishCompaction(CompactionStatus.RUNNING, sessionId, runId, publishId, record.getId(), detail);

        try {
            long summaryMaxLen = Math.min(
                    (long) (maxOutputTokens * OUTPUT_RATIO),
                    Math.min(SUMMARY_MAX_TOKENS_CAP, charsToTokens(compactInput.text().length()) * 2 / 3));
            summaryMaxLen = Math.max(SUMMARY_MIN_TOKENS, summaryMaxLen);
            String newSummary = callLLM(
                    CompactionPromptConstant.buildPrompt(session.getSummary(), compactInput.text(), summaryMaxLen),
                    route, sessionId, runId, publishId, (int) summaryMaxLen);

            if (newSummary == null || newSummary.isBlank()) {
                String reason = "压缩模型未返回内容";
                compactRecordSpi.updateFailed(record.getId(), reason);
                publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
                return CompactionOutcome.NO_PROGRESS;
            }
            long summaryTokens = charsToTokens(newSummary.length());
            // compact record 是 HistoryLoader 的权威来源：先落记录（摘要 + 游标同一条 UPDATE）。
            compactRecordSpi.updateCompleted(record.getId(), session.getSummary(), newSummary,
                    summaryTokens, compactInput.lastRunId());
            // session.summary 是辅助展示字段；失败不影响历史正确性，best-effort。
            try {
                sessionSpi.updateSummary(sessionId, newSummary, LocalDateTime.now().toString(), route.getId());
            } catch (Exception e) {
                log.warn("Update session summary failed for session {} (compact record already committed)", sessionId, e);
            }
            publishCompaction(CompactionStatus.COMPLETED, sessionId, runId, publishId, record.getId(), detail);
            return CompactionOutcome.COMPLETED;
        } catch (Exception e) {
            log.warn("Compaction failed for session {}", sessionId, e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            compactRecordSpi.updateFailed(record.getId(), reason);
            publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
            return CompactionOutcome.FAILED;
        }
    }

    static boolean isCompactionEligible(String status) {
        return RunStatus.COMPLETED.name().equals(status)
                || RunStatus.FAILED.name().equals(status)
                || RunStatus.CANCELLED.name().equals(status);
    }

    private void publishCompaction(CompactionStatus status, Long sessionId, Long runId,
                                   String publishId, Long compactRecordId, String detail) {
        eventPublisher.publishEvent(new RuntimeEventVO(status,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setPublishId(publishId)
                        .setCompactRecordId(compactRecordId)
                        .setAssistantReply(detail)));
    }

    /**
     * 按「最老优先」连续纳入候选 run，直到 token 上限。返回实际纳入范围（游标依据）。
     * 第一条就超限时截断该 run 并停止，绝不跳过它去压缩更新的 run。
     */
    CompactInput buildCompactInput(Long sessionId, List<RunVO> runs, long tokenLimit) {
        StringBuilder sb = new StringBuilder();
        long used = 0;
        int included = 0;
        Long lastRunId = null;
        boolean truncated = false;
        long limit = Math.max(1L, tokenLimit);
        for (RunVO r : runs) {
            String runText = renderRun(sessionId, r);
            long tokens = charsToTokens(runText.length());
            if (runText.isBlank()) {
                // 空 run 无需摘要内容，但可以安全地纳入游标（否则会永久阻塞后续历史）。
                lastRunId = r.getId();
                included++;
                continue;
            }
            if (used + tokens > limit) {
                if (included == 0) {
                    int remainingChars = Math.max(200,
                            (int) (limit * RunnerConstants.TOKEN_ESTIMATE_DIVISOR));
                    runText = truncateWithMarker(runText, remainingChars);
                    sb.append(runText);
                    lastRunId = r.getId();
                    included = 1;
                    truncated = true;
                }
                break;
            }
            sb.append(runText);
            used += tokens;
            lastRunId = r.getId();
            included++;
        }
        return new CompactInput(sb.toString(), lastRunId, included, truncated);
    }

    /** 渲染单个 run 供摘要使用；与估算共用同一份内容，避免「估了参数却摘要不到」的偏差。 */
    private String renderRun(Long sessionId, RunVO r) {
        StringBuilder sb = new StringBuilder();
        if (r.getUserMessage() != null && !r.getUserMessage().isBlank()) {
            sb.append(RunnerConstants.COMPACTION_USER_PREFIX)
                    .append(truncateField(r.getUserMessage(), COMPACTION_FIELD_MAX_CHARS))
                    .append(RunnerConstants.COMPACTION_NEWLINE);
        }

        List<AgentToolCallRecordVO> calls = new ArrayList<>(toolCallRecordSpi.listBySessionId(sessionId, r.getId()));
        Collections.reverse(calls);
        for (AgentToolCallRecordVO c : calls) {
            boolean succeeded = ToolCallRecordStatus.SUCCEEDED.name().equals(c.getStatus());
            String result = succeeded
                    ? ToolResultCompressor.compress(c.getArtifact(), RunnerConstants.COMPACTION_TOOL_RESULT_MAX_CHARS)
                    : "error: " + truncateField(c.getErrorMessage() != null ? c.getErrorMessage() : "unknown",
                            COMPACTION_ARGS_MAX_CHARS);
            String artifact = result != null ? result : "";
            sb.append("Tool[").append(c.getToolName()).append("]: ").append(artifact).append("\n");
            if (c.getArgumentsJson() != null && !c.getArgumentsJson().isBlank()) {
                sb.append("ToolArgs[").append(c.getToolName()).append("]: ")
                        .append(truncateField(c.getArgumentsJson(), COMPACTION_ARGS_MAX_CHARS))
                        .append("\n");
            }
        }

        if (r.getAssistantReply() != null && !r.getAssistantReply().isBlank()) {
            sb.append(RunnerConstants.COMPACTION_ASSISTANT_PREFIX)
                    .append(truncateField(r.getAssistantReply(), COMPACTION_FIELD_MAX_CHARS))
                    .append(RunnerConstants.COMPACTION_NEWLINE);
        }
        return sb.toString();
    }

    private static String truncateField(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int head = Math.max(1, maxChars * 2 / 3);
        int tail = Math.max(0, maxChars - head);
        return text.substring(0, head) + "\n... [truncated " + (text.length() - maxChars) + " chars] ...\n"
                + (tail > 0 ? text.substring(text.length() - tail) : "");
    }

    private static String truncateWithMarker(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int head = Math.max(1, maxChars * 2 / 3);
        int tail = Math.max(0, maxChars - head);
        return text.substring(0, head) + "\n... [truncated " + (text.length() - maxChars) + " chars] ...\n"
                + (tail > 0 ? text.substring(text.length() - tail) : "");
    }

    record CompactInput(String text, Long lastRunId, int includedRuns, boolean truncated) {
    }

    private long estimateMessagesTokens(List<ChatMessageDTO> messages) {
        int textChars = 0;
        long imageTokens = 0;
        for (ChatMessageDTO msg : messages) {
            if (msg.getContent() instanceof String text) {
                textChars += text.length();
            } else if (msg.getContent() instanceof List<?> parts) {
                for (Object part : parts) {
                    if (!(part instanceof ChatMessagePartDTO p)) {
                        continue;
                    }
                    if (p.getText() != null) {
                        textChars += p.getText().length();
                    } else if (ChatMessagePartDTO.TYPE_IMAGE_URL.equals(p.getType()) && p.getImageUrl() != null) {
                        imageTokens += IMAGE_PART_TOKENS;
                    }
                }
            }
            if (msg.getToolCalls() != null) {
                for (ToolCallDTO tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null && tc.getFunction().getArguments() != null) {
                        textChars += tc.getFunction().getArguments().length();
                    }
                }
            }
        }
        long tokens = charsToTokens(textChars) + imageTokens;
        log.debug("estimateMessagesTokens length:{}", tokens);
        return tokens;
    }

    private String callLLM(String messagesText, ModelRouteFullVO route, Long sessionId, Long runId,
                           String publishId, int hardMaxTokens) {
        try {
            ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                    .setModel(route.getModelName())
                    .setStream(true)
                    .setMessages(List.of(
                            new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM)
                                    .setContent(CompactionPromptConstant.getSystemPrompt()),
                            new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent(messagesText)));
            llmRequestConfigurer.applyGlobal(request);
            // 摘要长度必须由请求硬上限兜底：prompt 里的「MUST NOT exceed」只是自然语言要求。
            // 放在 applyGlobal 之后，避免被全局 sampling 配置覆盖。
            request.setMaxTokens(hardMaxTokens);
            String apiKeyValue = resolveApiKey(route);
            StringBuilder content = new StringBuilder();
            var future = kernelLlmService.stream(
                    route.getCompany(), route.getBaseUrl(), apiKeyValue, route.getModelName(),
                    request,
                    event -> {
                        switch (event) {
                            // 压缩输出实时打到前端对话框（分割线下的正文）：
                            // 用 compaction_output 子类型，避免被当成主 Agent 的 assistant/reasoning 行。
                            case LLMEvent.TextDelta t -> {
                                content.append(t.text());
                                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                        new RuntimeEventDataVO()
                                                .setSessionId(sessionId)
                                                .setRunId(runId)
                                                .setResponse(t.text())
                                                .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                                .setReasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_OUTPUT)
                                                .setPublishId(publishId)));
                            }
                            // 摘要模型的思考不展示（避免和压缩正文混在一起）。
                            case LLMEvent.ReasoningDelta r -> {
                            }
                            default -> {
                            }
                        }
                    },
                    new LlmInteractionMeta().setRunId(runId).setSessionId(sessionId)
                            .setInteractionType(LlmInteractionType.COMPACTION));
            future.get(agentRuntimeProperties.getLlm().getStreamTimeout().getSeconds(), TimeUnit.SECONDS);
            return content.toString();
        } catch (Exception e) {
            log.warn("LLM compaction call failed", e);
        }
        return "";
    }

    private String resolveApiKey(ModelRouteFullVO route) {
        if (route.getApiKeyId() == null) return "";
        try {
            return apiKeySpi.getApiKeyValue(route.getApiKeyId());
        } catch (Exception e) {
            log.warn("Failed to resolve API key", e);
            return "";
        }
    }
}
