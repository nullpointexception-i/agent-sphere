package com.buukle.agent.runtime.kernel.loader;

import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.runner.LiveToolResultPolicy;
import com.buukle.agent.runtime.kernel.util.ScreenshotObservationSupport;
import com.buukle.agent.util.json.JsonUtils;
import com.buukle.agent.util.json.ToolCallReason;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryLoader {

    /** run-local 压缩摘要的 marker（leading system；每次压缩替换旧副本）。 */
    public static final String RUN_SUMMARY_PREFIX = "\n\n[Current run summary]\n";

    private static final TypeReference<List<ChatMessageDTO>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };

    private final RunSpi runSpi;
    private final AgentCompactRecordSpi compactRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final AgentLlmInteractionRecordSpi interactionSpi;
    private final AgentRuntimeProperties agentRuntimeProperties;
    private final com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver attachmentResolver;

    /** 消息 + 当前 run 的起点下标（-1 = 本次没有载入当前 run）。 */
    public record HistoryResult(List<ChatMessageDTO> messages, int currentRunStart) {
    }

    public List<ChatMessageDTO> load(Long sessionId, Long excludeRunId) {
        return loadResult(sessionId, excludeRunId, false).messages();
    }

    public List<ChatMessageDTO> load(Long sessionId, Long excludeRunId, boolean restoreCurrentRun) {
        return loadResult(sessionId, excludeRunId, restoreCurrentRun).messages();
    }

    /**
     * @param restoreCurrentRun true = wait/澄清恢复：恢复当前 run 的上下文（优先 run-local summary+tail，
     *                          否则用持久化 tool 历史 + 逐轮 assistant 正文重建）。
     *                          false = 新 run / session 压缩重组：当前 run 的尾部由调用方保留，
     *                          这里只补 run-local summary 前缀，不重放 tail。
     */
    public HistoryResult loadResult(Long sessionId, Long excludeRunId, boolean restoreCurrentRun) {
        List<ChatMessageDTO> messages = new ArrayList<>();
        int currentRunStart = -1;

        AgentCompactRecordVO latestCompact = compactRecordSpi.getLatestCompleted(sessionId);
        if (latestCompact != null && latestCompact.getSummaryAfter() != null
                && !latestCompact.getSummaryAfter().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent(RunnerConstants.HISTORY_SUMMARY_PREFIX + latestCompact.getSummaryAfter()));
        }

        Long afterRunId = latestCompact != null && latestCompact.getCompactedUptoRunId() != null
                ? latestCompact.getCompactedUptoRunId() : 0L;
        List<RunVO> runs = runSpi.listRunsBySessionAfterId(sessionId, afterRunId);

        // 当前 run 的 run-local 压缩：summary 始终作为 leading system；tail 只在恢复时注入
        // （session 压缩重组时 tail 由 SessionRunner 的 currentRunTail 保留，避免重复）。
        AgentCompactRecordVO runCompact = excludeRunId != null
                ? compactRecordSpi.getLatestCompletedByRunId(excludeRunId) : null;
        boolean hasRunSummary = runCompact != null && runCompact.getSummaryAfter() != null
                && !runCompact.getSummaryAfter().isBlank();
        List<ChatMessageDTO> runTail = null;
        if (hasRunSummary) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent(RUN_SUMMARY_PREFIX + runCompact.getSummaryAfter()));
            if (restoreCurrentRun) {
                runTail = parseTail(runCompact.getTailJson());
            }
        }

        // Detect if any previous run was cancelled — inject a context hint
        boolean hasCancelledRun = runs.stream().anyMatch(
                r -> RunStatus.CANCELLED.name().equals(r.getStatus())
                        && (excludeRunId == null || !excludeRunId.equals(r.getId())));
        if (hasCancelledRun) {
            String originalUserMessage = runs.stream()
                    .filter(r -> RunStatus.CANCELLED.name().equals(r.getStatus())
                            && (excludeRunId == null || !excludeRunId.equals(r.getId())))
                    .map(RunVO::getUserMessage)
                    .filter(msg -> msg != null && !msg.isBlank())
                    .findFirst().orElse("");
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_SYSTEM)
                    .setContent("[The previous run was cancelled. "
                            + (originalUserMessage.isBlank()
                                ? "The user may be continuing their original request."
                                : "The user's original request was: \"" + originalUserMessage + "\". Read history carefully and continue.")
                            + " Do NOT ask 'what do you want to do' — execute the task.]"));
        }

        boolean includeCurrent = restoreCurrentRun && !hasRunSummary && excludeRunId != null;

        // Collect all tool call records per run (current run only when needed for reconstruction)
        Map<Long, List<AgentToolCallRecordVO>> callsByRun = new LinkedHashMap<>();
        for (RunVO run : runs) {
            boolean current = excludeRunId != null && excludeRunId.equals(run.getId());
            // 若 run-local tail 损坏需要回退，也要把当前 run 的 tool 记录读出来
            if (current && !includeCurrent && !restoreCurrentRun) {
                continue;
            }
            List<AgentToolCallRecordVO> calls = new ArrayList<>(toolCallRecordSpi.listBySessionId(sessionId, run.getId()));
            Collections.reverse(calls);
            if (!calls.isEmpty()) {
                callsByRun.put(run.getId(), calls);
            }
        }

        // Budget: accumulate from newest to oldest, mark over-budget entries
        Set<Long> overBudget = new HashSet<>();
        int budgetUsed = 0;
        List<Long> runIds = runs.stream().map(RunVO::getId).toList();
        for (int i = runIds.size() - 1; i >= 0; i--) {
            List<AgentToolCallRecordVO> calls = callsByRun.get(runIds.get(i));
            if (calls == null) continue;
            for (int j = calls.size() - 1; j >= 0; j--) {
                AgentToolCallRecordVO call = calls.get(j);
                if (LiveToolResultPolicy.isBrowserTool(call.getToolName())) {
                    continue;
                }
                String raw = call.getCompressedArtifact();
                int chars = raw != null ? raw.length() : 0;
                if (budgetUsed + chars > RunnerConstants.TOOL_RESULT_HISTORY_CHARS_BUDGET) {
                    overBudget.add(call.getId());
                } else {
                    budgetUsed += chars;
                }
            }
        }

        // Build completed runs chronologically (current run handled separately)
        for (RunVO run : runs) {
            if (excludeRunId != null && excludeRunId.equals(run.getId())) {
                continue;
            }
            appendRun(messages, run, callsByRun.get(run.getId()), overBudget);
        }

        // 当前 run：恢复时优先 tail；否则用持久化 tool 历史 + 逐轮 assistant 正文
        RunVO currentRun = runs.stream()
                .filter(r -> excludeRunId != null && excludeRunId.equals(r.getId()))
                .findFirst().orElse(null);
        if (includeCurrent && currentRun != null) {
            currentRunStart = messages.size();
            appendRunWithAssistantText(messages, currentRun, callsByRun.get(currentRun.getId()), overBudget,
                    restoreCurrentRun);
        } else if (restoreCurrentRun && hasRunSummary) {
            if (runTail != null && !runTail.isEmpty()) {
                currentRunStart = messages.size();
                messages.addAll(runTail);
            } else {
                log.warn("Run-local compaction tail unreadable for run {}; falling back to persisted tool history",
                        excludeRunId);
                if (currentRun != null) {
                    currentRunStart = messages.size();
                    appendRunWithAssistantText(messages, currentRun,
                            callsByRun.get(currentRun.getId()), overBudget, restoreCurrentRun);
                }
            }
        }

        return new HistoryResult(messages, currentRunStart);
    }

    private void appendRun(List<ChatMessageDTO> messages, RunVO run,
                           List<AgentToolCallRecordVO> calls, Set<Long> overBudget) {
        appendRunWithAssistantText(messages, run, calls, overBudget, false);
    }

    /**
     * run 的历史重建：tool 记录按时间配对到各轮 CHAT_REPLY，assistant 正文取 reply_content；
     * reasoning 只写入 assistant.reasoning_content，不写入正文。配对失败时回退为普通 tool 记录重建。
     *
     * @param replayScreenshots 仅当前 run 恢复时为 true：从 artifact 重新注入最近的截图观察消息
     */
    private void appendRunWithAssistantText(List<ChatMessageDTO> messages, RunVO run,
                                            List<AgentToolCallRecordVO> calls,
                                            Set<Long> overBudget, boolean replayScreenshots) {
        int start = messages.size();
        appendRunWithAssistantTextAttempt(messages, run, calls, overBudget, replayScreenshots);
        // 结构自检：assistant(tool_calls) 之后必须紧跟覆盖全部 tool_call_id 的 tool 消息。
        // 不满足时模型端会直接 400（insufficient tool messages following tool_calls），
        // 这里宁可退化成「一条 assistant + 一条 tool」的朴素重建，也不能把非法序列发出去。
        if (!hasValidToolCallPairing(messages.subList(start, messages.size()))) {
            log.warn("Rebuilt history for run {} violates tool_calls adjacency; falling back to per-call rebuild",
                    run.getId());
            messages.subList(start, messages.size()).clear();
            appendToolCallFallback(messages, run, calls, overBudget, replayScreenshots);
        }
    }

    private void appendRunWithAssistantTextAttempt(List<ChatMessageDTO> messages, RunVO run,
                                                   List<AgentToolCallRecordVO> calls,
                                                   Set<Long> overBudget, boolean replayScreenshots) {
        if (run.getUserMessage() != null && !run.getUserMessage().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_USER)
                    .setContent(run.getUserMessage()));
        }
        List<AgentToolCallRecordVO> allCalls = calls != null ? calls : List.of();
        Set<Long> screenshotCallIds = replayScreenshots
                ? pickReplayScreenshotCalls(allCalls) : Collections.emptySet();
        ScreenshotReplayBudget screenshotBudget = replayScreenshots
                ? new ScreenshotReplayBudget(agentRuntimeProperties.getRunner().getScreenshotReplayMaxBytes())
                : null;
        List<AgentLlmInteractionRecordVO> interactions;
        try {
            interactions = interactionSpi.listByRunIdAsc(run.getId());
        } catch (Exception e) {
            log.warn("Load interactions failed for run {}; falling back to tool records", run.getId(), e);
            interactions = List.of();
        }
        if (interactions == null || interactions.isEmpty()) {
            appendToolCalls(messages, allCalls, overBudget);
            replayScreenshotObservations(messages, allCalls, screenshotCallIds, screenshotBudget);
            addFinalAssistantReply(messages, run);
            return;
        }

        int idx = 0;
        // 有 turn_no 时按轮次精确分组，彻底摆脱时间窗口；老数据（迁移前）回退到毫秒窗口。
        // 子 Agent 的 tool 记录 turn_no 为空，会落到回退分支，行为与改动前一致。
        Map<Integer, List<AgentToolCallRecordVO>> callsByTurn = indexByTurn(allCalls);
        boolean turnKeyed = callsByTurn != null
                && interactions.stream().allMatch(x -> x.getTurnNo() != null)
                // 同一 turn 出现多条记录（历史 attempt 残留）时无法一一对应，回退到时间窗口
                && interactions.stream().map(AgentLlmInteractionRecordVO::getTurnNo).distinct().count()
                        == interactions.size();
        for (int i = 0; i < interactions.size(); i++) {
            AgentLlmInteractionRecordVO cur = interactions.get(i);
            AgentLlmInteractionRecordVO next = i + 1 < interactions.size() ? interactions.get(i + 1) : null;
            List<AgentToolCallRecordVO> turnCalls;
            if (turnKeyed) {
                List<AgentToolCallRecordVO> hit = callsByTurn.remove(cur.getTurnNo());
                turnCalls = hit != null ? hit : List.of();
            } else {
                turnCalls = new ArrayList<>();
                while (idx < allCalls.size() && inWindow(allCalls.get(idx), cur, next)) {
                    turnCalls.add(allCalls.get(idx++));
                }
            }
            String content = cur.getReplyContent();
            boolean hasText = content != null && !content.isBlank();
            if (hasText || !turnCalls.isEmpty()) {
                ChatMessageDTO assistant = new ChatMessageDTO().setRole(LlmApiConstant.ROLE_ASSISTANT);
                if (hasText) {
                    assistant.setContent(content);
                }
                if (!turnCalls.isEmpty()) {
                    assistant.setToolCalls(toToolCalls(turnCalls));
                }
                if (cur.getReasoning() != null && !cur.getReasoning().isBlank()) {
                    assistant.setReasoningContent(cur.getReasoning());
                }
                messages.add(assistant);
                // 先追加该轮全部 tool 结果，再统一注入截图观察：保证
                // assistant(tool_calls) → tool* 连续（多 tool 批次不被打断），与实时路径一致。
                for (AgentToolCallRecordVO call : turnCalls) {
                    messages.add(toolMessage(call, overBudget));
                }
                for (AgentToolCallRecordVO call : turnCalls) {
                    if (screenshotCallIds.contains(call.getId())) {
                        appendScreenshotObservation(messages, call, screenshotBudget);
                    }
                }
            }
        }
        // 最后一次交互之后产生的 tool 记录（如 wait 的落库）没有下一轮可配对，补一个 assistant(tool_calls)
        List<AgentToolCallRecordVO> remaining = turnKeyed
                ? callsByTurn.values().stream().flatMap(List::stream).toList()
                : (idx < allCalls.size() ? allCalls.subList(idx, allCalls.size()) : List.of());
        if (!remaining.isEmpty()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setToolCalls(toToolCalls(remaining)));
            for (AgentToolCallRecordVO call : remaining) {
                messages.add(toolMessage(call, overBudget));
            }
            for (AgentToolCallRecordVO call : remaining) {
                if (screenshotCallIds.contains(call.getId())) {
                    appendScreenshotObservation(messages, call, screenshotBudget);
                }
            }
        }
        addFinalAssistantReply(messages, run);
    }

    /**
     * 按 turn_no 给 tool 记录分组；任一记录缺少 turn_no（迁移前的历史行 / 子 Agent 的内部调用）
     * 就返回 null，让调用方整体回退到时间窗口 —— 半新半旧地混用两套配对会错位得更隐蔽。
     */
    private static Map<Integer, List<AgentToolCallRecordVO>> indexByTurn(List<AgentToolCallRecordVO> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        Map<Integer, List<AgentToolCallRecordVO>> byTurn = new LinkedHashMap<>();
        for (AgentToolCallRecordVO call : calls) {
            if (call.getTurnNo() == null) {
                return null;
            }
            byTurn.computeIfAbsent(call.getTurnNo(), k -> new ArrayList<>()).add(call);
        }
        return byTurn;
    }

    /**
     * 配对失败/结构非法时的兜底重建：一条 assistant + 一条 tool 的朴素序列，
     * 截图观察统一放到最后，绝不插进 tool 消息中间。
     */
    private void appendToolCallFallback(List<ChatMessageDTO> messages, RunVO run,
                                        List<AgentToolCallRecordVO> calls,
                                        Set<Long> overBudget, boolean replayScreenshots) {
        if (run.getUserMessage() != null && !run.getUserMessage().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_USER)
                    .setContent(run.getUserMessage()));
        }
        List<AgentToolCallRecordVO> allCalls = calls != null ? calls : List.of();
        Set<Long> screenshotCallIds = replayScreenshots
                ? pickReplayScreenshotCalls(allCalls) : Collections.emptySet();
        ScreenshotReplayBudget screenshotBudget = replayScreenshots
                ? new ScreenshotReplayBudget(agentRuntimeProperties.getRunner().getScreenshotReplayMaxBytes())
                : null;
        appendToolCalls(messages, allCalls, overBudget);
        replayScreenshotObservations(messages, allCalls, screenshotCallIds, screenshotBudget);
        addFinalAssistantReply(messages, run);
    }

    /**
     * 结构自检：每条带 tool_calls 的 assistant，其后必须紧跟连续的 tool 消息，
     * 且能覆盖它声明的全部 tool_call_id（OpenAI/DeepSeek 的硬约束）。
     */
    static boolean hasValidToolCallPairing(List<ChatMessageDTO> segment) {
        for (int i = 0; i < segment.size(); i++) {
            ChatMessageDTO message = segment.get(i);
            if (!LlmApiConstant.ROLE_ASSISTANT.equals(message.getRole())
                    || message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
                continue;
            }
            Set<String> expected = new HashSet<>();
            for (ToolCallDTO toolCall : message.getToolCalls()) {
                expected.add(toolCall.getId());
            }
            int j = i + 1;
            while (j < segment.size() && !expected.isEmpty()
                    && LlmApiConstant.ROLE_TOOL.equals(segment.get(j).getRole())) {
                expected.remove(segment.get(j).getToolCallId());
                j++;
            }
            if (!expected.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private Set<Long> pickReplayScreenshotCalls(List<AgentToolCallRecordVO> calls) {
        int max = agentRuntimeProperties.getRunner().getScreenshotReplayMax();
        if (max <= 0 || calls.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        // 只取最近 N 条：截图体积大，恢复时不需要把所有历史页面都塞回去
        for (int i = calls.size() - 1; i >= 0 && ids.size() < max; i--) {
            AgentToolCallRecordVO call = calls.get(i);
            String artifact = call.getArtifact() != null ? call.getArtifact() : call.getCompressedArtifact();
            if (ScreenshotObservationSupport.hasScreenshot(artifact)) {
                ids.add(call.getId());
            }
        }
        return ids;
    }

    private void replayScreenshotObservations(List<ChatMessageDTO> messages,
                                              List<AgentToolCallRecordVO> calls,
                                              Set<Long> screenshotCallIds,
                                              ScreenshotReplayBudget budget) {
        if (screenshotCallIds.isEmpty() || budget == null) {
            return;
        }
        for (AgentToolCallRecordVO call : calls) {
            if (screenshotCallIds.contains(call.getId())) {
                appendScreenshotObservation(messages, call, budget);
            }
        }
    }

    private void appendScreenshotObservation(List<ChatMessageDTO> messages,
                                             AgentToolCallRecordVO call,
                                             ScreenshotReplayBudget budget) {
        if (budget == null || budget.exhausted) {
            return;
        }
        String artifact = call.getArtifact() != null ? call.getArtifact() : call.getCompressedArtifact();
        ChatMessageDTO observation = ScreenshotObservationSupport.buildScreenshotObservation(
                attachmentResolver, artifact);
        if (observation == null) {
            return;
        }
        int bytes = observationBytes(observation);
        if (budget.usedBytes + bytes > budget.maxBytes) {
            budget.exhausted = true;
            log.warn("Screenshot replay budget exhausted: used={} max={}, remaining screenshots skipped",
                    budget.usedBytes, budget.maxBytes);
            return;
        }
        budget.usedBytes += bytes;
        messages.add(observation);
    }

    private static int observationBytes(ChatMessageDTO message) {
        int bytes = 0;
        if (message.getContent() instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof ChatMessagePartDTO p) {
                    if (p.getText() != null) {
                        bytes += p.getText().length();
                    }
                    if (p.getImageUrl() != null && p.getImageUrl().getUrl() != null) {
                        bytes += p.getImageUrl().getUrl().length();
                    }
                }
            }
        }
        return bytes;
    }

    /** 恢复时截图回放的字节预算（单次 HistoryLoader 调用内有效）。 */
    private static final class ScreenshotReplayBudget {
        private final int maxBytes;
        private int usedBytes;
        private boolean exhausted;

        private ScreenshotReplayBudget(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    private void addFinalAssistantReply(List<ChatMessageDTO> messages, RunVO run) {
        // 运行中的 run 通常为空；仅在确实有终态正文且没有任何逐轮正文时补一次，避免重复。
        if (run.getAssistantReply() == null || run.getAssistantReply().isBlank()) {
            return;
        }
        boolean hasAssistantText = messages.stream().anyMatch(m ->
                LlmApiConstant.ROLE_ASSISTANT.equals(m.getRole())
                        && m.getContent() instanceof String s && !s.isBlank());
        if (!hasAssistantText) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setContent(run.getAssistantReply()));
        }
    }

    /**
     * 判断一条 tool 记录是否落在某一轮 CHAT_REPLY 的时间窗口内。
     *
     * <p>必须用毫秒比较：VO 上的 createdAt 是秒级字符串，而「调用落在下一轮开始的同一秒」
     * 是真实存在的情况（线上 run 2810：tool 03:54:51.000042 与下一轮 03:54:51.842411）。
     * 按秒比较会把这次调用错配到下一轮，导致一条 assistant 带两个 tool_calls。
     */
    private static boolean inWindow(AgentToolCallRecordVO call,
                                    AgentLlmInteractionRecordVO cur,
                                    AgentLlmInteractionRecordVO next) {
        Long callMillis = call.getCreatedAtMillis();
        Long startMillis = cur.getCreatedAtMillis();
        Long endMillis = next != null ? next.getCreatedAtMillis() : null;
        if (callMillis != null && startMillis != null) {
            return callMillis >= startMillis && (endMillis == null || callMillis < endMillis);
        }
        // 兜底：老数据/未填充毫秒值时退回秒级字符串比较（仍是原有行为）
        return inWindowSecondPrecision(call.getCreatedAt(), cur.getCreatedAt(),
                next != null ? next.getCreatedAt() : null);
    }

    private static boolean inWindowSecondPrecision(String createdAt, String start, String end) {
        if (createdAt == null || start == null) {
            return false;
        }
        return createdAt.compareTo(start) >= 0 && (end == null || createdAt.compareTo(end) < 0);
    }

    private void appendToolCalls(List<ChatMessageDTO> messages, List<AgentToolCallRecordVO> calls,
                                 Set<Long> overBudget) {
        if (calls == null) {
            return;
        }
        for (AgentToolCallRecordVO c : calls) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setToolCalls(List.of(toolCall(c))));
            messages.add(toolMessage(c, overBudget));
        }
    }

    private static List<ToolCallDTO> toToolCalls(List<AgentToolCallRecordVO> calls) {
        List<ToolCallDTO> out = new ArrayList<>(calls.size());
        for (AgentToolCallRecordVO c : calls) {
            out.add(toolCall(c));
        }
        return out;
    }

    private static ToolCallDTO toolCall(AgentToolCallRecordVO c) {
        String callId = c.getCallId() != null ? c.getCallId()
                : RunnerConstants.FALLBACK_CALL_ID_PREFIX + c.getStepId();
        return ToolCallDTO.builder()
                .id(callId)
                .type(RunnerConstants.TOOL_TYPE_FUNCTION)
                .function(FunctionDefinitionDTO.builder()
                        .name(c.getToolName())
                        .arguments(ToolCallReason.strip(c.getArgumentsJson()))
                        .build())
                .build();
    }

    private ChatMessageDTO toolMessage(AgentToolCallRecordVO c, Set<Long> overBudget) {
        String callId = historyCallId(c);
        boolean browser = LiveToolResultPolicy.isBrowserTool(c.getToolName());
        String result;
        if (browser) {
            String raw = c.getArtifact() != null ? c.getArtifact() : c.getCompressedArtifact();
            int max = agentRuntimeProperties.getRunner().getLiveBrowserFullMaxChars();
            result = raw != null && raw.length() <= max
                    ? raw
                    : LiveToolResultPolicy.browserReference(callId, raw);
        } else if (overBudget.contains(c.getId())) {
            result = RunnerConstants.TOOL_RESULT_BUDGET_OMITTED;
        } else if (ToolCallRecordStatus.SUCCEEDED.name().equals(c.getStatus())) {
            result = c.getCompressedArtifact() != null
                    ? c.getCompressedArtifact()
                    : (c.getArtifact() != null ? c.getArtifact() : "");
        } else {
            result = c.getErrorMessage() != null
                    ? "{\"error\":\"" + c.getErrorMessage() + "\"}"
                    : "{\"error\":\"Tool execution failed\"}";
        }
        return new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_TOOL)
                .setToolCallId(callId)
                .setContent(result);
    }

    private static String historyCallId(AgentToolCallRecordVO c) {
        return c.getCallId() != null ? c.getCallId()
                : RunnerConstants.FALLBACK_CALL_ID_PREFIX + c.getStepId();
    }

    private static List<ChatMessageDTO> parseTail(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, MESSAGE_LIST_TYPE);
        } catch (Exception e) {
            return null;
        }
    }
}
