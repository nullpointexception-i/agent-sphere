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
import com.buukle.agent.model.dtvo.complete.FinishReason;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
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
import com.buukle.agent.runtime.kernel.runner.LiveToolResultPolicy;
import com.buukle.agent.runtime.kernel.util.SummarySanitizer;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final long SUMMARY_MIN_TOKENS = 256L;
    /** 自适应 target 至少要给 tail 留的 token（配置 min-tail-tokens 的兜底下限）。 */
    private static final long MIN_ADAPTIVE_TAIL_TOKENS = 2_000L;
    /** target 与触发预算之间至少留的余量，避免压缩后立刻再次触发。 */
    private static final long TARGET_HEADROOM_TOKENS = 1_024L;
    private static final double TARGET_HEADROOM_RATIO = 0.02;
    /** 单个 run 的用户/助手正文在压缩输入中的最大字符数（超出标记省略）。 */
    private static final int COMPACTION_FIELD_MAX_CHARS = 2000;
    /** 单个 run 的工具参数在压缩输入中的最大字符数。 */
    private static final int COMPACTION_ARGS_MAX_CHARS = 500;

    /** run-local tail 持久化上限（字符）。 */
    private static final int RUN_TAIL_MAX_CHARS = 64_000;
    /** wait/澄清挂起时的完整未摘要 tail 上限；超限则置空，回退 HistoryLoader 的 DB 重建。 */
    private static final int RUN_TAIL_SNAPSHOT_MAX_CHARS = 256_000;
    /** run-local tail 单条消息持久化上限（字符）。 */
    private static final int RUN_TAIL_MESSAGE_MAX_CHARS = 16_000;
    /** run-local 压缩输入的单条消息/工具结果上限。 */
    private static final int RUN_COMPACTION_MESSAGE_MAX_CHARS = 2_000;
    private static final int RUN_COMPACTION_TOOL_RESULT_MAX_CHARS = 1_000;
    private static final int RUN_COMPACTION_INPUT_MAX_CHARS = 900_000;
    private static final String RUN_TAIL_IMAGE_OMITTED = "[image omitted after run compaction]";
    private static final TypeReference<List<ChatMessageDTO>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    /** 一次压缩模型调用的结果（finish_reason/error 用于判断摘要是否完整）。 */
    private record CompactionCallResult(String content, String finishReason, String error,
                                        Integer completionTokens) {
    }

    /** 一次 run-local 压缩的真实结果（COMPLETED 时携带替换后的 messages）。 */
    public record RunCompactionResult(CompactionOutcome outcome, List<ChatMessageDTO> messages,
                                      int currentRunStart, Long compactRecordId,
                                      long postEstimate, long target) {
    }

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
    /** 压缩决策快照（供 runner 做 cooldown/硬上限判断，避免重复估算）。 */
    public record CompactionDecision(boolean shouldCompact, long messageTokens, long toolTokens,
                                     long fixedTokens, long estimated, long budget, long maxInputTokens) {
    }

    /** run-local 压缩目标计算快照（供日志和单测）。 */
    record CompactionTarget(long configuredTarget, long fixedTokens, long tailFloor,
                            long budget, long targetCeiling, long target, boolean feasible) {
    }

    public boolean shouldCompact(List<ChatMessageDTO> messages, ModelRouteFullVO route,
                                 List<ToolDefinitionDTO> tools, Integer effectiveMaxOutputTokens) {
        return evaluate(messages, route, tools, effectiveMaxOutputTokens).shouldCompact();
    }

    public CompactionDecision evaluate(List<ChatMessageDTO> messages, ModelRouteFullVO route,
                                       List<ToolDefinitionDTO> tools, Integer effectiveMaxOutputTokens) {
        if (route == null) {
            return new CompactionDecision(false, 0, 0, 0, 0, 0, 0);
        }
        Long routeId = route.getId();
        long maxInputTokens = resolveMaxInputTokens(route);
        long budget = resolveInputBudget(maxInputTokens, effectiveMaxOutputTokens);
        long toolTokens = estimateToolTokens(tools);
        long fixedTokens = estimateFixedTokens(messages, toolTokens);
        long messageTokens = estimateMessagesTokens(messages);
        if (fixedTokens >= maxInputTokens) {
            if (routeId == null || warnedInfeasibleCompaction.add(routeId)) {
                log.warn("Route {} cannot compact: fixed(leading system + tools)={} >= maxInput={}; "
                        + "sending oversized context instead", routeId, fixedTokens, maxInputTokens);
            }
            return new CompactionDecision(false, messageTokens, toolTokens, fixedTokens, fixedTokens, budget, maxInputTokens);
        }
        long estimated = (long) Math.ceil((messageTokens + toolTokens) * ESTIMATE_SAFETY_RATIO);
        log.debug("Compaction check: routeId={}, messageTokens={}, toolTokens={}, fixed={}, estimated={}, budget={}, maxInput={}, outputCap={}",
                routeId, messageTokens, toolTokens, fixedTokens, estimated, budget, maxInputTokens,
                effectiveMaxOutputTokens);
        return new CompactionDecision(estimated > budget, messageTokens, toolTokens, fixedTokens,
                estimated, budget, maxInputTokens);
    }

    /** 当前 run 已完成的 run-local 压缩次数（per-run 上限）。 */
    public long countCompletedRunCompactions(Long runId) {
        return compactRecordSpi.countCompletedByRunId(runId);
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

        long compactionInputLimit = (long) (maxInputTokens
                * agentRuntimeProperties.getRunner().getCompaction().getInputRatio());
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
            long summaryCap = resolveSummaryCap(maxInputTokens);
            long summaryMaxLen = Math.min(summaryCap,
                    Math.min(maxOutputTokens, charsToTokens(compactInput.text().length()) * 2 / 3));
            summaryMaxLen = Math.max(SUMMARY_MIN_TOKENS, summaryMaxLen);
            CompactionCallResult summaryResult = generateCompleteSummary(
                    CompactionPromptConstant.buildPrompt(session.getSummary(), compactInput.text(), summaryMaxLen),
                    route, sessionId, runId, publishId, summaryMaxLen, maxOutputTokens);
            if (!isCompleteSummary(summaryResult)) {
                String reason = incompleteReason(summaryResult);
                log.warn("Session compaction summary incomplete for session {}: {}", sessionId, reason);
                compactRecordSpi.updateFailed(record.getId(), reason);
                publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
                return CompactionOutcome.NO_PROGRESS;
            }
            String newSummary = summaryResult.content();
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
                    ? (LiveToolResultPolicy.isSkillLibraryTool(c.getToolName())
                        ? ToolResultCompressor.compressKeepArrays(c.getArtifact(),
                            RunnerConstants.COMPACTION_TOOL_RESULT_MAX_CHARS)
                        : ToolResultCompressor.compress(c.getArtifact(),
                            RunnerConstants.COMPACTION_TOOL_RESULT_MAX_CHARS))
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

    /**
     * 当前 run 的 run-local 压缩：只摘要「当前 user 之后、最后 4 条之前」的消息，
     * 保留当前 user + 最近 3 条作为 tail；不推进 session 游标。
     *
     * <p>压缩后消息结构：`[system prompt][session summary][current run summary][已完成历史][当前 user + tail]`。
     * run summary 放在 leading system 区，保持 Qwen 的 system-at-beginning 规则。
     */
    public RunCompactionResult compactRun(Long sessionId, Long runId, KernelContext ctx,
                                          List<ChatMessageDTO> messages, int currentRunStart,
                                          List<ToolDefinitionDTO> toolDefs,
                                          Integer effectiveMaxOutputTokens) {
        if (sessionId == null || runId == null || messages == null || messages.isEmpty()
                || currentRunStart < 0 || currentRunStart >= messages.size()) {
            return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, 0);
        }
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session == null) {
                return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, 0);
            }
            List<ModelRouteFullVO> routes = routeListBuilder.fromInstance(session.getAgentInstanceId());
            return fallbackRouteExecutor.execute(routes, (i, route) -> doCompactRun(
                    sessionId, runId, session, route, messages, currentRunStart, toolDefs,
                    effectiveMaxOutputTokens));
        } catch (Exception e) {
            log.warn("Run compaction failed for run {}", runId, e);
            return new RunCompactionResult(CompactionOutcome.FAILED, messages, currentRunStart, null, 0, 0);
        }
    }

    /**
     * wait/澄清挂起：把当前未摘要上下文（从当前 user 到最新消息）快照进最近一条 run-local 记录。
     * 这样 resume 不会用「上次压缩时」的旧 tail 丢掉之后的回合；超限则置空，让 HistoryLoader 回退 DB 重建。
     */
    public void snapshotRunTail(Long runId, List<ChatMessageDTO> messages, int currentRunStart) {
        if (runId == null || messages == null || currentRunStart < 0 || currentRunStart >= messages.size()) {
            return;
        }
        try {
            AgentCompactRecordVO latest = compactRecordSpi.getLatestCompletedByRunId(runId);
            if (latest == null) {
                return;
            }
            List<ChatMessageDTO> tail = new ArrayList<>();
            tail.add(messages.get(currentRunStart));
            for (int i = currentRunStart + 1; i < messages.size(); i++) {
                if (isWorkMessage(messages.get(i))) {
                    tail.add(messages.get(i));
                }
            }
            String json = serializeTailSnapshot(tail);
            compactRecordSpi.updateRunTail(runId, json);
        } catch (Exception e) {
            log.warn("Run tail snapshot failed for run {}", runId, e);
        }
    }

    CompactionTarget resolveCompactionTarget(long maxInputTokens, long budget, long prefixTokens,
                                             long toolTokens, long summaryReserve,
                                             AgentRuntimeProperties.RunnerConfig.CompactionConfig compaction) {
        long configured = (long) (maxInputTokens * compaction.getTargetRatio());
        long fixed = prefixTokens + toolTokens + summaryReserve;
        long minTail = Math.max(MIN_ADAPTIVE_TAIL_TOKENS, compaction.getMinTailTokens());
        long tailFloor = Math.max(minTail, (long) (maxInputTokens * compaction.getTailRatio()));
        long headroom = Math.max(TARGET_HEADROOM_TOKENS,
                (long) (maxInputTokens * TARGET_HEADROOM_RATIO));
        long ceiling = budget - headroom;
        if (!compaction.isAdaptiveTargetEnabled()) {
            return new CompactionTarget(configured, fixed, tailFloor, budget, ceiling,
                    configured, configured > fixed + MIN_ADAPTIVE_TAIL_TOKENS);
        }
        long adaptive = Math.max(configured, fixed + tailFloor);
        long target = Math.min(adaptive, ceiling);
        return new CompactionTarget(configured, fixed, tailFloor, budget, ceiling, target,
                target > fixed + MIN_ADAPTIVE_TAIL_TOKENS);
    }

    private RunCompactionResult doCompactRun(Long sessionId, Long runId, SessionVO session,
                                             ModelRouteFullVO route, List<ChatMessageDTO> messages,
                                             int currentRunStart, List<ToolDefinitionDTO> toolDefs,
                                             Integer effectiveMaxOutputTokens) {
        int userIndex = currentRunStart;
        long maxInputTokens = resolveMaxInputTokens(route);
        long maxOutputTokens = route.getMaxOutputTokens() != null ? route.getMaxOutputTokens()
                : RunnerConstants.DEFAULT_MAX_OUTPUT_TOKENS;
        var compaction = agentRuntimeProperties.getRunner().getCompaction();
        long toolTokens = estimateToolTokens(toolDefs);
        long prefixTokens = estimateMessagesTokens(messages.subList(0, userIndex + 1));
        long summaryCap = resolveSummaryCap(maxInputTokens);
        long summaryReserve = summaryCap;
        long budget = resolveInputBudget(maxInputTokens, effectiveMaxOutputTokens);
        CompactionTarget targetInfo = resolveCompactionTarget(
                maxInputTokens, budget, prefixTokens, toolTokens, summaryReserve, compaction);
        long target = targetInfo.target();
        if (!targetInfo.feasible()) {
            log.warn("Run compaction target infeasible: run={}, configured={}, fixed={}, tailFloor={}, "
                            + "budget={}, ceiling={}, target={}",
                    runId, targetInfo.configuredTarget(), targetInfo.fixedTokens(), targetInfo.tailFloor(),
                    targetInfo.budget(), targetInfo.targetCeiling(), target);
            return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, 0);
        }
        log.debug("Run compaction target: run={}, configured={}, adaptive={}, fixed={}, tailFloor={}, "
                        + "budget={}, ceiling={}, target={}",
                runId, targetInfo.configuredTarget(), compaction.isAdaptiveTargetEnabled(),
                targetInfo.fixedTokens(), targetInfo.tailFloor(), targetInfo.budget(),
                targetInfo.targetCeiling(), target);
        long availableForTail = target - targetInfo.fixedTokens();
        long tailBudgetTokens = Math.min((long) (maxInputTokens * compaction.getTailRatio()), availableForTail);
        TailSelection selection = selectRunTail(messages, userIndex, tailBudgetTokens);
        int tailStart = selection.tailStart();
        if (tailStart <= userIndex + 1 || tailStart > messages.size()) {
            log.info("Run compaction skipped: not enough current-run work messages (run={}, userIndex={}, size={})",
                    runId, userIndex, messages.size());
            return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, 0);
        }

        // 摘要输入只含 user/assistant/tool 工作消息，排除 time/todos/loop 等动态 system 槽
        List<ChatMessageDTO> toSummarize = workMessages(messages.subList(userIndex + 1, tailStart));
        String userText = truncateField(contentText(messages.get(userIndex).getContent()),
                RUN_COMPACTION_MESSAGE_MAX_CHARS);
        AgentCompactRecordVO previous = compactRecordSpi.getLatestCompletedByRunId(runId);
        String previousSummary = previous != null ? previous.getSummaryAfter() : null;

        long inputLimit = (long) (maxInputTokens * compaction.getInputRatio());
        int maxInputChars = Math.clamp(Math.round(inputLimit * RunnerConstants.TOKEN_ESTIMATE_DIVISOR), 1_000, RUN_COMPACTION_INPUT_MAX_CHARS);
        String rendered = renderChatMessagesForCompaction(toSummarize, maxInputChars);
        if (rendered.isBlank()) {
            return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, 0);
        }
        String compactPrompt = "User request:\n" + userText + "\n\nMessages:\n" + rendered;

        long summaryMaxLen = Math.min(summaryCap,
                Math.min(maxOutputTokens, charsToTokens(compactPrompt.length()) * 2 / 3));
        summaryMaxLen = Math.max(SUMMARY_MIN_TOKENS, summaryMaxLen);

        String publishId = RuntimeEventTypeConstant.PUBLISH_ID_COMPACT + sessionId + ":" + UUID.randomUUID();
        AgentCompactRecordVO record = compactRecordSpi.createRunRecord(sessionId, runId);
        compactRecordSpi.updateRunning(record.getId());
        String detail = "当前 run 合并 " + toSummarize.size() + " 条消息";
        publishCompaction(CompactionStatus.RUNNING, sessionId, runId, publishId, record.getId(), detail);

        try {
            CompactionCallResult summaryResult = generateCompleteSummary(
                    CompactionPromptConstant.buildPrompt(previousSummary, compactPrompt, summaryMaxLen),
                    route, sessionId, runId, publishId, summaryMaxLen, maxOutputTokens);
            if (!isCompleteSummary(summaryResult)) {
                String reason = incompleteReason(summaryResult);
                log.warn("Run compaction summary incomplete for run {}: {}", runId, reason);
                compactRecordSpi.updateFailed(record.getId(), reason);
                publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
                return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, target);
            }
            String summary = summaryResult.content();

            // 新消息：prefix（去掉旧 run summary）→ 新 run summary 插到 leading system 末尾 → 当前 user + tail
            List<ChatMessageDTO> prefix = new ArrayList<>(messages.subList(0, userIndex));
            prefix.removeIf(m -> LlmApiConstant.ROLE_SYSTEM.equals(m.getRole())
                    && m.getContent() instanceof String s && s.startsWith(HistoryLoader.RUN_SUMMARY_PREFIX));
            int insertAt = 0;
            while (insertAt < prefix.size()
                    && LlmApiConstant.ROLE_SYSTEM.equals(prefix.get(insertAt).getRole())) {
                insertAt++;
            }
            prefix.add(insertAt, new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent(HistoryLoader.RUN_SUMMARY_PREFIX + summary));
            List<ChatMessageDTO> tail = selection.tail();
            List<ChatMessageDTO> rebuilt = new ArrayList<>(prefix);
            int newCurrentRunStart = rebuilt.size();
            rebuilt.addAll(tail);

            // tail 的选择已按 tail-ratio 控制；持久化保留 64k 硬上限，
            // 以免把「最近一条浏览器全文」按路由预算截掉（浏览器策略优先）。
            String tailJson = serializeTailForPersistence(tail, RUN_TAIL_MAX_CHARS);
            long summaryTokens = charsToTokens(summary.length());
            compactRecordSpi.completeRunCompact(record.getId(), previousSummary, summary, summaryTokens, tailJson);
            publishCompaction(CompactionStatus.COMPLETED, sessionId, runId, publishId, record.getId(), detail);
            long after = (long) Math.ceil((estimateMessagesTokens(rebuilt) + toolTokens) * ESTIMATE_SAFETY_RATIO);
            log.info("Run compaction completed: run={}, summarizedMessages={}, tailMessages={}, summaryTokens={}, "
                            + "postEstimate={}, target={}", runId, toSummarize.size(), tail.size(), summaryTokens,
                    after, target);
            if (after > target) {
                log.warn("Run compaction still above target: run={}, postEstimate={}, target={}", runId, after, target);
            }
            return new RunCompactionResult(CompactionOutcome.COMPLETED, rebuilt, newCurrentRunStart,
                    record.getId(), after, target);
        } catch (Exception e) {
            log.warn("Run compaction failed for run {}", runId, e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            compactRecordSpi.updateFailed(record.getId(), reason);
            publishCompaction(CompactionStatus.FAILED, sessionId, runId, publishId, record.getId(), reason);
            return new RunCompactionResult(CompactionOutcome.FAILED, messages, currentRunStart, null, 0, 0);
        }
    }

    private record TailSelection(List<ChatMessageDTO> tail, int tailStart) {
    }

    /**
     * 从当前 user 之后选择最近的工作消息作为 tail：排除 time/todo/loop 等动态 system 槽；
     * 按 tail 预算从新到旧累计；始终包含最新一条浏览器结果（即使超出 tail 预算）；
     * 不切断 assistant(tool_calls)/tool 组。
     */
    private TailSelection selectRunTail(List<ChatMessageDTO> messages, int userIndex, long tailBudgetTokens) {
        int size = messages.size();
        if (userIndex < 0 || userIndex >= size) {
            return new TailSelection(List.of(), -1);
        }
        long budgetChars = Math.max(1_000L,
                (long) (tailBudgetTokens * RunnerConstants.TOKEN_ESTIMATE_DIVISOR));
        int selectedStart = size;
        long used = 0;
        for (int i = size - 1; i > userIndex; i--) {
            ChatMessageDTO m = messages.get(i);
            if (!isWorkMessage(m)) {
                continue;
            }
            long len = messageChars(m);
            if (selectedStart != size && used + len > budgetChars) {
                break;
            }
            used += len;
            selectedStart = i;
        }
        if (selectedStart == size) {
            selectedStart = userIndex + 1;
        }
        int latestBrowser = latestBrowserIndex(messages, userIndex);
        if (latestBrowser >= 0 && latestBrowser < selectedStart) {
            selectedStart = latestBrowser;
        }
        while (selectedStart > userIndex + 1
                && LlmApiConstant.ROLE_TOOL.equals(messages.get(selectedStart).getRole())) {
            selectedStart--;
        }
        List<ChatMessageDTO> tail = new ArrayList<>();
        tail.add(messages.get(userIndex));
        for (int i = selectedStart; i < size; i++) {
            if (isWorkMessage(messages.get(i))) {
                tail.add(messages.get(i));
            }
        }
        return new TailSelection(tail, selectedStart);
    }

    private static List<ChatMessageDTO> workMessages(List<ChatMessageDTO> messages) {
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessageDTO m : messages) {
            if (isWorkMessage(m)) {
                result.add(m);
            }
        }
        return result;
    }

    private static boolean isWorkMessage(ChatMessageDTO m) {
        String role = m.getRole();
        return role != null && !LlmApiConstant.ROLE_SYSTEM.equals(role);
    }

    private static long messageChars(ChatMessageDTO m) {
        long len = contentText(m.getContent()).length();
        if (m.getToolCalls() != null) {
            for (ToolCallDTO tc : m.getToolCalls()) {
                if (tc.getFunction() != null && tc.getFunction().getArguments() != null) {
                    len += tc.getFunction().getArguments().length();
                }
            }
        }
        return len;
    }

    private static int latestBrowserIndex(List<ChatMessageDTO> messages, int userIndex) {
        String browser = LiveToolResultPolicy.browserToolName();
        Map<String, String> callNames = new LinkedHashMap<>();
        for (ChatMessageDTO m : messages) {
            if (LlmApiConstant.ROLE_ASSISTANT.equals(m.getRole()) && m.getToolCalls() != null) {
                for (ToolCallDTO tc : m.getToolCalls()) {
                    if (tc.getId() != null && tc.getFunction() != null) {
                        callNames.put(tc.getId(), tc.getFunction().getName());
                    }
                }
            }
        }
        int latest = -1;
        for (int i = userIndex + 1; i < messages.size(); i++) {
            ChatMessageDTO m = messages.get(i);
            if (LlmApiConstant.ROLE_TOOL.equals(m.getRole())
                    && browser.equals(callNames.get(m.getToolCallId()))) {
                latest = i;
            }
        }
        return latest;
    }

    /** 把当前 run 的旧消息渲染成摘要输入；只含正文/工具调用/工具结果，明确不含 reasoning。 */
    private static String renderChatMessagesForCompaction(List<ChatMessageDTO> messages, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageDTO m : messages) {
            String role = m.getRole() != null ? m.getRole() : "unknown";
            String content = contentText(m.getContent());
            if (content != null && !content.isBlank()) {
                int cap = LlmApiConstant.ROLE_TOOL.equals(role)
                        ? RUN_COMPACTION_TOOL_RESULT_MAX_CHARS : RUN_COMPACTION_MESSAGE_MAX_CHARS;
                sb.append(role).append(": ").append(truncateField(content, cap)).append('\n');
            }
            if (m.getToolCalls() != null) {
                for (ToolCallDTO tc : m.getToolCalls()) {
                    String name = tc.getFunction() != null ? tc.getFunction().getName() : "";
                    String args = tc.getFunction() != null ? tc.getFunction().getArguments() : "";
                    sb.append("assistant tool_call ").append(name).append(": ")
                            .append(truncateField(args, COMPACTION_ARGS_MAX_CHARS)).append('\n');
                }
            }
        }
        return truncateWithMarker(sb.toString(), maxChars);
    }

    /** 只取文本正文；图片 part 用标记代替（不解析 data URL）。 */
    private static String contentText(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof ChatMessagePartDTO p) {
                    if (p.getText() != null) {
                        sb.append(p.getText());
                    } else if (ChatMessagePartDTO.TYPE_IMAGE_URL.equals(p.getType())) {
                        sb.append(RUN_TAIL_IMAGE_OMITTED);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static boolean isGroupStart(ChatMessageDTO message) {
        String role = message.getRole();
        return LlmApiConstant.ROLE_USER.equals(role) || LlmApiConstant.ROLE_ASSISTANT.equals(role);
    }

    /** tail 持久化：剥离图片 data URL、截断超长正文，并控制总大小。 */
    private static String serializeTailForPersistence(List<ChatMessageDTO> tail, int maxChars) {
        List<ChatMessageDTO> copy = sanitizedTailCopy(tail, RUN_TAIL_MESSAGE_MAX_CHARS);
        String json = JsonUtils.toJson(copy);
        // 保留当前 user（index 0），按「完整 assistant/tool 组」丢弃最旧的一组，避免孤儿 tool 消息
        while (json.length() > maxChars && copy.size() > 1) {
            int end = 2;
            while (end < copy.size() && !isGroupStart(copy.get(end))) {
                end++;
            }
            copy.subList(1, end).clear();
            json = JsonUtils.toJson(copy);
        }
        if (json.length() > maxChars && copy.get(0).getContent() instanceof String s) {
            copy.get(0).setContent(truncateField(s, RUN_TAIL_MESSAGE_MAX_CHARS));
            json = JsonUtils.toJson(copy);
        }
        return json;
    }

    /** 挂起快照：不丢弃任何组；超过上限返回 null，让 resume 回退 DB 重建。 */
    private static String serializeTailSnapshot(List<ChatMessageDTO> tail) {
        // 快照不逐条截断（模型当时看到的内容要完整恢复）；只受总上限约束
        String json = JsonUtils.toJson(sanitizedTailCopy(tail, Integer.MAX_VALUE));
        if (json != null && json.length() > RUN_TAIL_SNAPSHOT_MAX_CHARS) {
            log.warn("Run tail snapshot too large ({} chars > {}), falling back to DB reconstruction",
                    json.length(), RUN_TAIL_SNAPSHOT_MAX_CHARS);
            return null;
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessageDTO> sanitizedTailCopy(List<ChatMessageDTO> tail, int messageMaxChars) {
        List<ChatMessageDTO> copy = JsonUtils.parse(JsonUtils.toJson(tail), MESSAGE_LIST_TYPE);
        if (copy == null) {
            copy = new ArrayList<>(tail);
        }
        for (ChatMessageDTO m : copy) {
            Object content = m.getContent();
            if (content instanceof List<?> parts) {
                // JsonUtils 反序列化时 content 是 Object，元素会变成 Map，而不是 ChatMessagePartDTO
                List<Object> sanitized = new ArrayList<>(parts.size());
                for (Object p : parts) {
                    if (p instanceof ChatMessagePartDTO part
                            && ChatMessagePartDTO.TYPE_IMAGE_URL.equals(part.getType())) {
                        part.setType(ChatMessagePartDTO.TYPE_TEXT);
                        part.setText(RUN_TAIL_IMAGE_OMITTED);
                        part.setImageUrl(null);
                        sanitized.add(part);
                    } else if (p instanceof Map<?, ?> map
                            && (ChatMessagePartDTO.TYPE_IMAGE_URL.equals(map.get("type"))
                                || map.containsKey("image_url"))) {
                        Map<String, Object> text = new LinkedHashMap<>();
                        text.put("type", ChatMessagePartDTO.TYPE_TEXT);
                        text.put("text", RUN_TAIL_IMAGE_OMITTED);
                        sanitized.add(text);
                    } else {
                        sanitized.add(p);
                    }
                }
                m.setContent(sanitized);
            } else if (content instanceof String s && s.length() > messageMaxChars) {
                m.setContent(truncateField(s, messageMaxChars));
            }
        }
        return copy;
    }

    private CompactionCallResult callLLM(String messagesText, ModelRouteFullVO route, Long sessionId, Long runId,
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
            // 摘要不需要思考：全局 config 的 thinking=true 会把 4k 输出预算耗在 reasoning 上，
            // 实测出现 completion=4000、reasoning 5-11k、content 为空（压缩直接失败）。
            request.setThinking(new ThinkingDTO().setType("disabled"));
            // 摘要长度必须由请求硬上限兜底：prompt 里的「MUST NOT exceed」只是自然语言要求。
            // 放在 applyGlobal 之后，避免被全局 sampling 配置覆盖。
            request.setMaxTokens(hardMaxTokens);
            String apiKeyValue = resolveApiKey(route);
            StringBuilder content = new StringBuilder();
            java.util.concurrent.atomic.AtomicReference<String> finishReasonRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> errorRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> usageRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
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
                            case LLMEvent.Finish f -> {
                                finishReasonRef.set(f.reason());
                                usageRef.set(f.usage());
                            }
                            case LLMEvent.Error e -> errorRef.set(e.message());
                            default -> {
                            }
                        }
                    },
                    new LlmInteractionMeta().setRunId(runId).setSessionId(sessionId)
                            .setInteractionType(LlmInteractionType.COMPACTION));
            future.get(agentRuntimeProperties.getLlm().getStreamTimeout().getSeconds(), TimeUnit.SECONDS);
            Integer completionTokens = usageInt(usageRef.get(), "completion_tokens", "completionTokens");
            return new CompactionCallResult(content.toString(), finishReasonRef.get(), errorRef.get(), completionTokens);
        } catch (Exception e) {
            log.warn("LLM compaction call failed", e);
            return new CompactionCallResult("", null, e.getMessage(), null);
        }
    }

    /**
     * 生成不超过目标 token 的完整摘要。
     *
     * <p>首轮允许模型完整输出（生成上限高于目标），避免 max_tokens 硬截断；若结果超预算或
     * finish_reason=length，则把该结果作为输入继续压缩，最多 {@code summary-compress-rounds} 轮。
     * 仍不满足时裁剪到目标预算。
     */
    private CompactionCallResult generateCompleteSummary(String prompt, ModelRouteFullVO route,
                                                         Long sessionId, Long runId, String publishId,
                                                         long targetTokens, long maxOutputTokens) {
        int rounds = Math.max(1, agentRuntimeProperties.getRunner().getCompaction().getSummaryCompressRounds());
        int generationCap = (int) summaryGenerationCap(targetTokens, maxOutputTokens);
        String currentPrompt = prompt;
        CompactionCallResult last = null;
        for (int round = 1; round <= rounds; round++) {
            CompactionCallResult result = callLLM(currentPrompt, route, sessionId, runId, publishId, generationCap);
            last = result;
            if (result == null || result.error() != null
                    || result.content() == null || result.content().isBlank()) {
                log.warn("Compaction summary round {}/{} failed: {}", round, rounds,
                        result == null ? "null result"
                                : (result.error() != null ? result.error() : "empty content"));
                continue;
            }
            long tokens = charsToTokens(result.content().length());
            if (isCompleteSummary(result) && tokens <= targetTokens) {
                log.info("Compaction summary complete: round={}/{}, finishReason={}, completionTokens={}, tokens={}, target={}, maxTokens={}",
                        round, rounds, result.finishReason(), result.completionTokens(),
                        tokens, targetTokens, generationCap);
                return result;
            }
            log.warn("Compaction summary over budget or incomplete: round={}/{}, finishReason={}, completionTokens={}, tokens={}, target={}; compressing again",
                    round, rounds, result.finishReason(), result.completionTokens(), tokens, targetTokens);
            currentPrompt = CompactionPromptConstant.buildReducePrompt(result.content(), targetTokens);
        }
        if (last == null || last.content() == null || last.content().isBlank()) {
            return last;
        }
        String trimmed = SummarySanitizer.trimToTokenBudget(last.content(), targetTokens);
        if (trimmed == null || trimmed.isBlank()) {
            log.warn("Compaction summary trim failed after {} rounds: originalTokens={}, target={}",
                    rounds, charsToTokens(last.content().length()), targetTokens);
            return new CompactionCallResult("", last.finishReason(), "摘要裁剪失败", last.completionTokens());
        }
        log.warn("Compaction summary trimmed after {} rounds: originalTokens={}, trimmedTokens={}, target={}",
                rounds, charsToTokens(last.content().length()), charsToTokens(trimmed.length()), targetTokens);
        return new CompactionCallResult(trimmed, null, null, last.completionTokens());
    }

    private static long summaryGenerationCap(long targetTokens, long maxOutputTokens) {
        long desired = Math.max(targetTokens * 2, targetTokens + 2_000L);
        long routeCap = Math.max(256L, maxOutputTokens);
        return Math.max(SUMMARY_MIN_TOKENS, Math.min(routeCap, desired));
    }

    private static boolean isCompleteSummary(CompactionCallResult result) {
        if (result == null || result.error() != null
                || result.content() == null || result.content().isBlank()) {
            return false;
        }
        if (FinishReason.LENGTH.equals(result.finishReason())
                || FinishReason.CONTENT_FILTER.equals(result.finishReason())) {
            return false;
        }
        return SummarySanitizer.isComplete(result.content());
    }

    private static String incompleteReason(CompactionCallResult result) {
        if (result == null) return "压缩模型未返回内容";
        if (result.error() != null) return "压缩调用失败: " + result.error();
        if (result.content() == null || result.content().isBlank()) return "压缩模型未返回内容";
        if (FinishReason.LENGTH.equals(result.finishReason())) return "摘要被 max_tokens 截断";
        if (FinishReason.CONTENT_FILTER.equals(result.finishReason())) return "摘要被内容安全策略拦截";
        return "摘要结构不完整";
    }

    private static Integer usageInt(Map<String, Object> usage, String... keys) {
        if (usage == null) return null;
        for (String key : keys) {
            Object value = usage.get(key);
            if (value instanceof Number n) return n.intValue();
        }
        return null;
    }

    long resolveSummaryCap(long maxInputTokens) {
        var compaction = agentRuntimeProperties.getRunner().getCompaction();
        long ratioCap = (long) (maxInputTokens * compaction.getSummaryMaxRatio());
        return Math.max(512L, Math.min(compaction.getSummaryMaxTokens(), ratioCap));
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
