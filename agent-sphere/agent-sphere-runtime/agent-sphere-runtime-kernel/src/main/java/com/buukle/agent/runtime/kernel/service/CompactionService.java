package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.AgentRuntimeProperties.RunnerConfig.CompactionTier;
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
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.CompactionStatus;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.prompt.CompactionPromptConstant;
import com.buukle.agent.runtime.kernel.runner.tool.LiveToolResultPolicy;
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

    /** Estimation safety factor: Chinese/JSON character density varies a lot, so keep a 10% margin to avoid "estimate below the threshold but actually over the context". */
    private static final double ESTIMATE_SAFETY_RATIO = 1.1;
    /** Tool schemas / pure JSON are mostly ASCII, with a much higher chars/token than Chinese prose; using 1.8 would badly over-estimate. */
    private static final double JSON_TOKEN_ESTIMATE_DIVISOR = 3.5;
    /** Conservative token cost of a single image (base64 is not parsed; estimated at the high end for common multimodal routes). */
    private static final long IMAGE_PART_TOKENS = 1000L;
    /** Minimum margin reserved for output within the input budget (tokens). */
    private static final long MIN_OUTPUT_MARGIN_TOKENS = 512L;
    /** Margin ratio reserved for output within the input budget (of max_input). */
    private static final double OUTPUT_MARGIN_RATIO = 0.05;
    /** Budget floor (of max_input): even if the output quota config is abnormal, the budget is not squeezed to the point of not working. */
    private static final double MIN_INPUT_BUDGET_RATIO = 0.25;
    private static final long SUMMARY_MIN_TOKENS = 256L;
    /** Safety margin when calibrated with real prompt_tokens. */
    private static final double CALIBRATION_SAFETY_RATIO = 1.05;
    private static final double CALIBRATION_MIN = 0.8;
    private static final double CALIBRATION_MAX = 1.5;
    private static final double CALIBRATION_ALPHA = 0.3;
    private static final double CALIBRATION_REJECT_MIN = 0.5;
    private static final double CALIBRATION_REJECT_MAX = 2.0;
    private static final long CALIBRATION_TTL_MS = TimeUnit.HOURS.toMillis(24);
    /** Minimum tokens the adaptive target must leave for the tail (fallback lower bound of the min-tail-tokens config). */
    private static final long MIN_ADAPTIVE_TAIL_TOKENS = 2_000L;
    /** Minimum margin between target and trigger budget, so compaction does not retrigger immediately. */
    private static final long TARGET_HEADROOM_TOKENS = 1_024L;
    private static final double TARGET_HEADROOM_RATIO = 0.02;
    /** Max characters of a single run's user/assistant body in the compaction input (excess is marked as omitted). */
    private static final int COMPACTION_FIELD_MAX_CHARS = 2000;
    /** Max characters of a single run's tool arguments in the compaction input. */
    private static final int COMPACTION_ARGS_MAX_CHARS = 500;

    /** Persistence cap for the run-local tail (characters). */
    private static final int RUN_TAIL_MAX_CHARS = 64_000;
    /** Cap on the full un-summarized tail while suspended for wait/clarification; above it the tail is emptied and HistoryLoader falls back to DB reconstruction. */
    private static final int RUN_TAIL_SNAPSHOT_MAX_CHARS = 256_000;
    /** Per-message persistence cap for the run-local tail (characters). */
    private static final int RUN_TAIL_MESSAGE_MAX_CHARS = 16_000;
    /** Per-message / per-tool-result cap for run-local compaction input. */
    private static final int RUN_COMPACTION_MESSAGE_MAX_CHARS = 2_000;
    private static final int RUN_COMPACTION_TOOL_RESULT_MAX_CHARS = 1_000;
    private static final int RUN_COMPACTION_INPUT_MAX_CHARS = 900_000;
    private static final String RUN_TAIL_IMAGE_OMITTED = "[image omitted after run compaction]";
    private static final TypeReference<List<ChatMessageDTO>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    /** Result of one compaction model call (finish_reason/error used to judge whether the summary is complete). */
    private record CompactionCallResult(String content, String finishReason, String error,
                                        Integer completionTokens) {
    }

    /** Actual prompt_tokens / character-estimate ratio for a route (EWMA). */
    private record Calibration(double ratio, long updatedAt) {
    }

    /** Real result of one run-local compaction (carries the replaced messages when COMPLETED). */
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
    /** Warn only once per route missing max_input_tokens, to avoid logging every loop. */
    private final Set<Long> warnedMissingMaxInput = ConcurrentHashMap.newKeySet();
    /** Warn only once per route where "compaction cannot bring the context within budget", to avoid logging every loop. */
    private final Set<Long> warnedInfeasibleCompaction = ConcurrentHashMap.newKeySet();
    /** prompt_tokens calibration factor per route; in-process, TTL 24h. */
    private final Map<Long, Calibration> calibrationByRoute = new ConcurrentHashMap<>();

    /**
     * Whether compaction is needed.
     *
     * <p><b>Tool schemas must be counted in the estimate</b>: the request body = messages + tools + output; the JSON of 10 tools is about 10k
     * tokens, and counting only messages leads to "the estimate is below the threshold but the context is already exceeded" — the production 32k route (tools 11.7k tokens)
     * is exactly how it returned 400 at an actual 32,852 tokens.
     *
     * <p>Without calibration add a 10% safety factor (chars/1.8 ×1.1); with real prompt_tokens calibration use
     * {@code char estimate × calibration × 1.05}. The route's {@code max_output_tokens}
     * does not participate in the request (the request's max_tokens is controlled by sampling config), so it is not deducted from the total context here either;
     * the 0.7 budgetRatio itself already reserves 30% for output.
     *
     * <p><b>Skip compaction only when the fixed overhead alone cannot fit the model</b>: the main system prompt and tool schemas are carried every loop,
     * and compaction can only compress history. If {@code leading system + tools} already exceeds {@code max_input_tokens} (a hard limit, no longer multiplied by
     * the safety factor), compaction is impossible and would only burn 3 compaction calls every loop for nothing (production route 4 = the 20k test route).
     * Return false in that case and let the request go out over-context directly: if the model accepts it, continue; if it rejects it, fail with a 400 terminal state.
     * Note the threshold must be the hard limit rather than 0.7×budget — the latter would permanently disable compaction for routes where "tools take the bulk but the real content still fits"
     * (such as a 32k route), and history would keep growing until real overflow.
     */
    /** Compaction decision snapshot (for the runner to judge cooldown/hard limit without re-estimating). */
    public record CompactionDecision(boolean shouldCompact, long messageTokens, long toolTokens,
                                     long fixedTokens, long estimated, long budget, long maxInputTokens) {
    }

    /** Snapshot of the run-local compaction target computation (for logs and unit tests). */
    record CompactionTarget(long configuredTarget, long fixedTokens, long tailFloor,
                            long budget, long targetCeiling, long target, boolean feasible) {
    }

    public boolean shouldCompact(List<ChatMessageDTO> messages, ModelRouteFullVO route,
                                 List<ToolDefinitionDTO> tools) {
        return evaluate(messages, route, tools).shouldCompact();
    }

    public CompactionDecision evaluate(List<ChatMessageDTO> messages, ModelRouteFullVO route,
                                       List<ToolDefinitionDTO> tools) {
        if (route == null) {
            return new CompactionDecision(false, 0, 0, 0, 0, 0, 0);
        }
        Long routeId = route.getId();
        long maxInputTokens = resolveMaxInputTokens(route);
        long budget = resolveInputBudget(maxInputTokens);
        long toolTokens = estimateToolTokens(tools);
        long fixedRawTokens = estimateFixedTokens(messages, toolTokens);
        Double calibration = calibrationFor(routeId);
        long fixedTokens = applyCalibration(fixedRawTokens, calibration);
        long messageTokens = estimateMessagesTokens(messages);
        if (fixedTokens >= maxInputTokens) {
            if (routeId == null || warnedInfeasibleCompaction.add(routeId)) {
                log.warn("Route {} cannot compact: fixed(leading system + tools)={} >= maxInput={}; "
                        + "sending oversized context instead", routeId, fixedTokens, maxInputTokens);
            }
            return new CompactionDecision(false, messageTokens, toolTokens, fixedTokens, fixedTokens, budget, maxInputTokens);
        }
        long estimated = applyCalibration(messageTokens + toolTokens, calibration);
        log.debug("Compaction check: routeId={}, messageTokens={}, toolTokens={}, fixed={}, estimated={}, budget={}, maxInput={}",
                routeId, messageTokens, toolTokens, fixedTokens, estimated, budget, maxInputTokens);
        return new CompactionDecision(estimated > budget, messageTokens, toolTokens, fixedTokens,
                estimated, budget, maxInputTokens);
    }

    /** Character-estimated tokens of the current request (uncalibrated), for the LLM interaction record's actual/estimated ratio. */
    public long estimatePromptTokens(List<ChatMessageDTO> messages, List<ToolDefinitionDTO> tools) {
        return estimateMessagesTokens(messages) + estimateToolTokens(tools);
    }

    /** Updates the route calibration factor with a real request's prompt_tokens / estimate. */
    public void recordCalibration(Long routeId, Integer estimated, Integer actual) {
        if (routeId == null || estimated == null || actual == null || estimated <= 0 || actual <= 0) {
            return;
        }
        double ratio = actual / (double) estimated;
        if (ratio < CALIBRATION_REJECT_MIN || ratio > CALIBRATION_REJECT_MAX) {
            log.warn("Ignore abnormal token calibration: route={}, actual={}, estimated={}, ratio={}",
                    routeId, actual, estimated, ratio);
            return;
        }
        double clamped = Math.max(CALIBRATION_MIN, Math.min(CALIBRATION_MAX, ratio));
        Calibration prev = calibrationByRoute.get(routeId);
        double next = prev == null ? clamped : (1 - CALIBRATION_ALPHA) * prev.ratio()
                + CALIBRATION_ALPHA * clamped;
        calibrationByRoute.put(routeId, new Calibration(next, System.currentTimeMillis()));
        log.debug("Token calibration updated: route={}, actual={}, estimated={}, ratio={}, calibration={}",
                routeId, actual, estimated, ratio, next);
    }

    private Double calibrationFor(Long routeId) {
        if (routeId == null) {
            return null;
        }
        Calibration calibration = calibrationByRoute.get(routeId);
        if (calibration == null) {
            return null;
        }
        if (System.currentTimeMillis() - calibration.updatedAt() > CALIBRATION_TTL_MS) {
            calibrationByRoute.remove(routeId);
            return null;
        }
        return calibration.ratio();
    }

    private static long applyCalibration(long rawTokens, Double calibration) {
        if (rawTokens <= 0) {
            return 0;
        }
        double factor = calibration == null
                ? ESTIMATE_SAFETY_RATIO : calibration * CALIBRATION_SAFETY_RATIO;
        return (long) Math.ceil(rawTokens * factor);
    }

    /** Selects a tier by maxInputTokens; linearly interpolates between tiers and extrapolates proportionally beyond the last tier. */
    public CompactionTier resolveTier(long maxInputTokens) {
        var tiers = agentRuntimeProperties.getRunner().getCompaction().getTiers();
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        List<CompactionTier> sorted = tiers.stream()
                .filter(t -> t != null && t.getMaxInputTokens() > 0)
                .sorted(java.util.Comparator.comparingLong(CompactionTier::getMaxInputTokens))
                .toList();
        if (sorted.isEmpty()) {
            return null;
        }
        CompactionTier first = sorted.get(0);
        if (maxInputTokens <= first.getMaxInputTokens()) {
            return tier(maxInputTokens,
                    scale(first.getTriggerTokens(), maxInputTokens, first.getMaxInputTokens()));
        }
        CompactionTier last = sorted.get(sorted.size() - 1);
        if (maxInputTokens > last.getMaxInputTokens()) {
            return tier(maxInputTokens,
                    scale(last.getTriggerTokens(), maxInputTokens, last.getMaxInputTokens()));
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            CompactionTier lower = sorted.get(i);
            CompactionTier upper = sorted.get(i + 1);
            if (maxInputTokens <= upper.getMaxInputTokens()) {
                double fraction = (maxInputTokens - lower.getMaxInputTokens())
                        / (double) (upper.getMaxInputTokens() - lower.getMaxInputTokens());
                return tier(maxInputTokens,
                        interpolate(lower.getTriggerTokens(), upper.getTriggerTokens(), fraction));
            }
        }
        return last;
    }

    /**
     * Nominal output limit for chat requests: instance config.max_tokens first, then route max_output_tokens.
     *
     * <p>Independent of tier; existing routes without it fall back to 16k.
     */
    public int resolveNominalChatMaxOutput(Integer instanceMaxOutput, Long routeMaxOutput) {
        if (instanceMaxOutput != null && instanceMaxOutput > 0) {
            return instanceMaxOutput;
        }
        if (routeMaxOutput != null && routeMaxOutput > 0) {
            return (int) Math.min(Integer.MAX_VALUE, routeMaxOutput.longValue());
        }
        return (int) RunnerConstants.DEFAULT_ROUTE_MAX_OUTPUT_TOKENS;
    }

    /**
     * Actual output limit for a single turn: after leaving room for input plus a 5% margin, take the smaller of the nominal limit and the remaining space.
     *
     * <p>When the output is below {@link RunnerConstants#MIN_CHAT_OUTPUT_TOKENS} the caller must compact or fail,
     * and must not send a request that is bound to exceed the context.
     */
    public int resolveRequestMaxOutput(long maxInputTokens, long estimatedInputTokens, int nominalChatMaxOutput) {
        long margin = Math.max(MIN_OUTPUT_MARGIN_TOKENS,
                (long) Math.ceil(maxInputTokens * OUTPUT_MARGIN_RATIO));
        long remaining = maxInputTokens - estimatedInputTokens - margin;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(nominalChatMaxOutput, remaining);
    }

    private static CompactionTier tier(long maxInputTokens, long triggerTokens) {
        CompactionTier tier = new CompactionTier();
        tier.setMaxInputTokens(maxInputTokens);
        tier.setTriggerTokens(triggerTokens);
        return tier;
    }

    private static long scale(long value, long target, long base) {
        if (value <= 0 || target <= 0 || base <= 0) {
            return 0;
        }
        return Math.max(1L, Math.round(value * (target / (double) base)));
    }

    private static long interpolate(long lower, long upper, double fraction) {
        return Math.max(1L, Math.round(lower + (upper - lower) * fraction));
    }

    /** Number of run-local compactions already completed for the current run (per-run cap). */
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
     * Compaction trigger threshold: looks only at the input budget, decoupled from the output limit.
     *
     * <p>The tier's trigger-tokens is already configured so that "the trigger point still leaves 8k of output"; when output does not fit,
     * {@link #resolveRequestMaxOutput(long, long, int)} shrinks max_tokens for this turn instead of compacting early.
     */
    private long resolveInputBudget(long maxInputTokens) {
        CompactionTier tier = resolveTier(maxInputTokens);
        long ratioBudget = tier != null && tier.getTriggerTokens() > 0
                ? tier.getTriggerTokens()
                : (long) (maxInputTokens
                * agentRuntimeProperties.getRunner().getCompaction().getBudgetRatio());
        long floor = Math.max(1L, (long) (maxInputTokens * MIN_INPUT_BUDGET_RATIO));
        return Math.max(floor, ratioBudget);
    }

    /**
     * The part compaction cannot reduce: the leading contiguous system messages (main prompt + summary/cancel hints injected by the history layer) + tool schemas.
     * Only leading system counts as fixed overhead — tail system (time/todos/budget) is rebuilt every loop and also cannot be compacted, but it is small,
     * so it is not counted here to avoid harming edge cases.
     */
    private long estimateFixedTokens(List<ChatMessageDTO> messages, long toolTokens) {
        long systemTokens = 0;
        if (messages != null) {
            List<ChatMessageDTO> leadingSystem = new ArrayList<>();
            for (ChatMessageDTO msg : messages) {
                if (!LlmRoles.SYSTEM.equals(msg.getRole())) {
                    break;
                }
                leadingSystem.add(msg);
            }
            systemTokens = estimateMessagesTokens(leadingSystem);
        }
        return systemTokens + toolTokens;
    }

    /** Chinese prose character count → tokens (rounded up; divisor in {@link RunnerConstants#TOKEN_ESTIMATE_DIVISOR}). */
    private static long charsToTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (long) Math.ceil(chars / RunnerConstants.TOKEN_ESTIMATE_DIVISOR);
    }

    /** Tool schema / JSON character count → tokens: mostly ASCII, using a separate divisor to avoid over-estimating by Chinese density. */
    private static long jsonCharsToTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (long) Math.ceil(chars / JSON_TOKEN_ESTIMATE_DIVISOR);
    }

    /** Tool definitions are also part of the prompt: convert the tools JSON character count into tokens. */
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
     * Compacts the oldest stretch of "finished history after the cursor".
     *
     * <p>Only finished runs are compacted: RUNNING (including the current run) and parked runs are not summary candidates — they are carried by in-memory messages,
     * kept as-is during compaction reassembly, and not replayed from the database. The cursor only advances to the <b>last run actually written into the summary</b>.
     *
     * <p>Candidates are included consecutively from oldest to newest until the compaction input budget is exhausted; if the very first run exceeds the budget,
     * truncate it at field level / as a whole and mark it omitted, then stop (never skip it to compact a newer run).
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

        String detail = "Merged " + compactInput.includedRuns() + " historical runs"
                + (compactInput.truncated() ? " (oversized content was truncated)" : "");
        // publishId must be unique per compaction: multiple compactions of the same session each get their own row (timeline reuses/seals by it).
        String publishId = RuntimeEventTypeConstant.PUBLISH_ID_COMPACT + sessionId + ":" + UUID.randomUUID();
        // Create the record first: failures still get an audit row, and timeline can attach its ref immediately (after refresh the body is parsed from summary_after).
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
            // The compact record is HistoryLoader's authoritative source: persist it first (summary + cursor in the same UPDATE).
            compactRecordSpi.updateCompleted(record.getId(), session.getSummary(), newSummary,
                    summaryTokens, compactInput.lastRunId());
            // session.summary is an auxiliary display field; a failure does not affect history correctness, best-effort.
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
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(runId)
                        .publishId(publishId)
                        .compactRecordId(compactRecordId)
                        .assistantReply(detail).build()));
    }

    /**
     * Includes candidate runs consecutively "oldest first" up to the token limit. Returns the actual included range (the basis for the cursor).
     * If the first one already exceeds the limit, truncate that run and stop; never skip it to compact a newer run.
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
                // An empty run needs no summary content, but can be safely included in the cursor (otherwise it would block later history forever).
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

    /** Renders a single run for the summary; shares the same content as the estimate to avoid the drift of "arguments were estimated but not summarized". */
    private String renderRun(Long sessionId, RunVO r) {
        StringBuilder sb = new StringBuilder();
        if (r.getUserMessage() != null && !r.getUserMessage().isBlank()) {
            sb.append(RunnerConstants.COMPACTION_USER_PREFIX)
                    .append(truncateField(r.getUserMessage(), COMPACTION_FIELD_MAX_CHARS))
                    .append(RunnerConstants.COMPACTION_NEWLINE);
        }

        List<AgentToolCallRecordVO> calls = new ArrayList<>(toolCallRecordSpi.listBySessionId(sessionId, r.getId()));
        // A sub-agent's internal calls are not the parent agent's conversation content and must not appear in the summary (the parent side has only one delegate result).
        calls.removeIf(c -> c.getSubAgentRunId() != null);
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

    long estimateMessagesTokens(List<ChatMessageDTO> messages) {
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
            // reasoning is also part of the real request: after resume/suspension it must be counted in the budget, otherwise the context is under-estimated
            if (msg.getReasoningContent() != null) {
                textChars += msg.getReasoningContent().length();
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
     * Run-local compaction of the current run: summarizes only messages "after the current user and before the last 4",
     * keeping the current user + the most recent 3 as the tail; the session cursor does not advance.
     *
     * <p>Message structure after compaction: `[system prompt][session summary][current run summary][finished history][current user + tail]`.
     * The run summary goes into the leading system area, preserving Qwen's system-at-beginning rule.
     */
    public RunCompactionResult compactRun(Long sessionId, Long runId, KernelContext ctx,
                                          List<ChatMessageDTO> messages, int currentRunStart,
                                          List<ToolDefinitionDTO> toolDefs) {
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
                    sessionId, runId, session, route, messages, currentRunStart, toolDefs));
        } catch (Exception e) {
            log.warn("Run compaction failed for run {}", runId, e);
            return new RunCompactionResult(CompactionOutcome.FAILED, messages, currentRunStart, null, 0, 0);
        }
    }

    /**
     * wait/clarification suspension: snapshot the current un-summarized context (from the current user to the newest message) into the most recent run-local record.
     * This way resume does not use the stale tail "from the last compaction" and lose later turns; above the limit, empty it so HistoryLoader falls back to DB reconstruction.
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
                                             int currentRunStart, List<ToolDefinitionDTO> toolDefs) {
        int userIndex = currentRunStart;
        long maxInputTokens = resolveMaxInputTokens(route);
        long maxOutputTokens = route.getMaxOutputTokens() != null ? route.getMaxOutputTokens()
                : RunnerConstants.DEFAULT_MAX_OUTPUT_TOKENS;
        var compaction = agentRuntimeProperties.getRunner().getCompaction();
        Double calibration = calibrationFor(route.getId());
        long toolTokens = applyCalibration(estimateToolTokens(toolDefs), calibration);
        long prefixTokens = applyCalibration(estimateMessagesTokens(
                messages.subList(0, userIndex + 1)), calibration);
        long summaryCap = resolveSummaryCap(maxInputTokens);
        long summaryReserve = summaryCap;
        long budget = resolveInputBudget(maxInputTokens);
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
        long tailBudgetTokens = Math.min(
                (long) (maxInputTokens * compaction.getTailRatio()), availableForTail);
        TailSelection selection = selectRunTail(messages, userIndex, tailBudgetTokens);
        int tailStart = selection.tailStart();
        if (tailStart <= userIndex + 1 || tailStart > messages.size()) {
            log.info("Run compaction skipped: not enough current-run work messages (run={}, userIndex={}, size={})",
                    runId, userIndex, messages.size());
            return new RunCompactionResult(CompactionOutcome.NO_PROGRESS, messages, currentRunStart, null, 0, 0);
        }

        // Summary input contains only user/assistant/tool working messages, excluding dynamic system slots such as time/todos/loop
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
        String detail = "Current run merged " + toSummarize.size() + " messages";
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

            // New messages: prefix (old run summary removed) → new run summary inserted at the end of the leading system → current user + tail
            List<ChatMessageDTO> prefix = new ArrayList<>(messages.subList(0, userIndex));
            prefix.removeIf(m -> LlmRoles.SYSTEM.equals(m.getRole())
                    && m.getContent() instanceof String s && s.startsWith(HistoryLoader.RUN_SUMMARY_PREFIX));
            int insertAt = 0;
            while (insertAt < prefix.size()
                    && LlmRoles.SYSTEM.equals(prefix.get(insertAt).getRole())) {
                insertAt++;
            }
            prefix.add(insertAt, new ChatMessageDTO()
                    .setRole(LlmRoles.SYSTEM)
                    .setContent(HistoryLoader.RUN_SUMMARY_PREFIX + summary));
            List<ChatMessageDTO> tail = selection.tail();
            List<ChatMessageDTO> rebuilt = new ArrayList<>(prefix);
            int newCurrentRunStart = rebuilt.size();
            rebuilt.addAll(tail);

            // Tail selection is already controlled by tail-ratio; persistence keeps a 64k hard cap,
            // so "the most recent full browser page" is not cut by the route budget (browser policy takes precedence).
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
     * Selects recent working messages after the current user as the tail: excludes dynamic system slots such as time/todo/loop;
     * accumulates newest-to-oldest within the tail budget; always includes the latest browser result (even beyond the tail budget);
     * does not split assistant(tool_calls)/tool groups.
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
                && LlmRoles.TOOL.equals(messages.get(selectedStart).getRole())) {
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
        return role != null && !LlmRoles.SYSTEM.equals(role);
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
            if (LlmRoles.ASSISTANT.equals(m.getRole()) && m.getToolCalls() != null) {
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
            if (LlmRoles.TOOL.equals(m.getRole())
                    && browser.equals(callNames.get(m.getToolCallId()))) {
                latest = i;
            }
        }
        return latest;
    }

    /** Renders the current run's old messages into summary input; contains only bodies/tool calls/tool results, explicitly no reasoning. */
    private static String renderChatMessagesForCompaction(List<ChatMessageDTO> messages, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageDTO m : messages) {
            String role = m.getRole() != null ? m.getRole() : "unknown";
            String content = contentText(m.getContent());
            if (content != null && !content.isBlank()) {
                int cap = LlmRoles.TOOL.equals(role)
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

    /** Takes only the text body; image parts are replaced with a marker (data URLs are not parsed). */
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
        return LlmRoles.USER.equals(role) || LlmRoles.ASSISTANT.equals(role);
    }

    /** Tail persistence: strips image data URLs, truncates over-long bodies, and controls the total size. */
    private static String serializeTailForPersistence(List<ChatMessageDTO> tail, int maxChars) {
        List<ChatMessageDTO> copy = sanitizedTailCopy(tail, RUN_TAIL_MESSAGE_MAX_CHARS);
        String json = JsonUtils.toJson(copy);
        // Keep the current user (index 0) and drop the oldest "complete assistant/tool group", avoiding orphan tool messages
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

    /** Suspension snapshot: no group is dropped; returns null above the cap so resume falls back to DB reconstruction. */
    private static String serializeTailSnapshot(List<ChatMessageDTO> tail) {
        // The snapshot is not truncated message by message (what the model saw at the time must be restored in full); only the total cap applies
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
                // During JsonUtils deserialization content is an Object and its elements become Map instead of ChatMessagePartDTO
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
                        ChatMessagePartDTO omitted = new ChatMessagePartDTO();
                        omitted.setType(ChatMessagePartDTO.TYPE_TEXT);
                        omitted.setText(RUN_TAIL_IMAGE_OMITTED);
                        sanitized.add(omitted);
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
                            new ChatMessageDTO().setRole(LlmRoles.SYSTEM)
                                    .setContent(CompactionPromptConstant.getSystemPrompt()),
                            new ChatMessageDTO().setRole(LlmRoles.USER).setContent(messagesText)));
            llmRequestConfigurer.applyGlobal(request);
            // The summary does not need thinking: the global config's thinking=true would spend the output budget on reasoning,
            // and it was observed that completion=4000, reasoning 5–11k, content empty (compaction fails outright).
            // Turn off all three fields together, covering the different switches of DeepSeek/Qwen/OpenAI-compatible gateways.
            request.setThinking(new ThinkingDTO().setType("disabled"));
            request.setEnableThinking(false);
            request.setReasoningEffort(null);
            // The summary length must be backstopped by a hard request limit: "MUST NOT exceed" in the prompt is only a natural-language request.
            // Placed after applyGlobal so it is not overridden by the global sampling config.
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
                            // Compaction output is streamed to the frontend conversation in real time (the body under the divider):
                            // uses the compaction_output subtype so it is not treated as the main agent's assistant/reasoning rows.
                            case LLMEvent.TextDelta t -> {
                                content.append(t.text());
                                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                        RuntimeEventDataVO.builder()
                                                .sessionId(sessionId)
                                                .runId(runId)
                                                .response(t.text())
                                                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                                .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_OUTPUT)
                                                .publishId(publishId).build()));
                            }
                            // The summary model's thinking is not displayed (to avoid mixing it with the compaction body).
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
     * Generates a complete summary that does not exceed the target token count.
     *
     * <p>The first round lets the model output in full (generation limit above the target) to avoid a hard max_tokens cut; if the result exceeds the budget or
     * finish_reason=length, feed that result back as input and compress again, for at most {@code summary-compress-rounds} rounds.
     * If it still does not fit, trim it to the target budget.
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
            return new CompactionCallResult("", last.finishReason(), "Summary trimming failed", last.completionTokens());
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
        if (result == null) return "Compaction model returned no content";
        if (result.error() != null) return "Compaction call failed: " + result.error();
        if (result.content() == null || result.content().isBlank()) return "Compaction model returned no content";
        if (FinishReason.LENGTH.equals(result.finishReason())) return "Summary was truncated by max_tokens";
        if (FinishReason.CONTENT_FILTER.equals(result.finishReason())) return "Summary was blocked by the content safety policy";
        return "Summary structure is incomplete";
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
