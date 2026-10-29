package com.buukle.agent.runtime.kernel.loader;

import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.runner.LiveToolResultPolicy;
import com.buukle.agent.util.json.JsonUtils;
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
                String raw = calls.get(j).getCompressedArtifact();
                int chars = raw != null ? raw.length() : 0;
                if (budgetUsed + chars > RunnerConstants.TOOL_RESULT_HISTORY_CHARS_BUDGET) {
                    overBudget.add(calls.get(j).getId());
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
            appendCurrentRunWithAssistantText(messages, currentRun, callsByRun.get(currentRun.getId()), overBudget);
        } else if (restoreCurrentRun && hasRunSummary) {
            if (runTail != null && !runTail.isEmpty()) {
                currentRunStart = messages.size();
                messages.addAll(runTail);
            } else {
                log.warn("Run-local compaction tail unreadable for run {}; falling back to persisted tool history",
                        excludeRunId);
                if (currentRun != null) {
                    currentRunStart = messages.size();
                    appendCurrentRunWithAssistantText(messages, currentRun,
                            callsByRun.get(currentRun.getId()), overBudget);
                }
            }
        }

        return new HistoryResult(messages, currentRunStart);
    }

    private void appendRun(List<ChatMessageDTO> messages, RunVO run,
                           List<AgentToolCallRecordVO> calls, Set<Long> overBudget) {
        if (run.getUserMessage() != null && !run.getUserMessage().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_USER)
                    .setContent(run.getUserMessage()));
        }
        appendToolCalls(messages, calls, overBudget);
        if (run.getAssistantReply() != null && !run.getAssistantReply().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setContent(run.getAssistantReply()));
        }
    }

    /**
     * 当前 run 的恢复：tool 记录按时间配对到各轮 CHAT_REPLY，assistant 正文取 reply_content；
     * reasoning 明确不进入上下文。配对失败时回退为普通 tool 记录重建。
     */
    private void appendCurrentRunWithAssistantText(List<ChatMessageDTO> messages, RunVO run,
                                                   List<AgentToolCallRecordVO> calls,
                                                   Set<Long> overBudget) {
        if (run.getUserMessage() != null && !run.getUserMessage().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_USER)
                    .setContent(run.getUserMessage()));
        }
        List<AgentToolCallRecordVO> allCalls = calls != null ? calls : List.of();
        String browserTool = LiveToolResultPolicy.browserToolName();
        Long latestBrowserCallId = null;
        for (AgentToolCallRecordVO c : allCalls) {
            if (browserTool.equals(c.getToolName())) {
                latestBrowserCallId = c.getId();
            }
        }
        List<AgentLlmInteractionRecordVO> interactions;
        try {
            interactions = interactionSpi.listByRunIdAsc(run.getId());
        } catch (Exception e) {
            log.warn("Load interactions failed for run {}; falling back to tool records", run.getId(), e);
            interactions = List.of();
        }
        if (interactions == null || interactions.isEmpty()) {
            appendToolCalls(messages, allCalls, overBudget);
            addFinalAssistantReply(messages, run);
            return;
        }

        int idx = 0;
        for (int i = 0; i < interactions.size(); i++) {
            AgentLlmInteractionRecordVO cur = interactions.get(i);
            String nextStart = i + 1 < interactions.size() ? interactions.get(i + 1).getCreatedAt() : null;
            List<AgentToolCallRecordVO> turnCalls = new ArrayList<>();
            while (idx < allCalls.size() && inWindow(allCalls.get(idx).getCreatedAt(), cur.getCreatedAt(), nextStart)) {
                turnCalls.add(allCalls.get(idx++));
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
                messages.add(assistant);
                for (AgentToolCallRecordVO call : turnCalls) {
                    messages.add(toolMessage(call, overBudget,
                            call.getId() != null && call.getId().equals(latestBrowserCallId)));
                }
            }
        }
        // 最后一次交互之后产生的 tool 记录（如 wait 的落库）没有下一轮可配对，补一个 assistant(tool_calls)
        if (idx < allCalls.size()) {
            List<AgentToolCallRecordVO> remaining = allCalls.subList(idx, allCalls.size());
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT)
                    .setToolCalls(toToolCalls(remaining)));
            for (AgentToolCallRecordVO call : remaining) {
                messages.add(toolMessage(call, overBudget,
                        call.getId() != null && call.getId().equals(latestBrowserCallId)));
            }
        }
        addFinalAssistantReply(messages, run);
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

    private static boolean inWindow(String createdAt, String start, String end) {
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
            messages.add(toolMessage(c, overBudget, false));
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
                        .arguments(c.getArgumentsJson())
                        .build())
                .build();
    }

    private static ChatMessageDTO toolMessage(AgentToolCallRecordVO c, Set<Long> overBudget, boolean keepFull) {
        String callId = c.getCallId() != null ? c.getCallId()
                : RunnerConstants.FALLBACK_CALL_ID_PREFIX + c.getStepId();
        String result;
        if (keepFull) {
            // 最近一条浏览器结果恢复全文（与 live 策略一致）；完整 artifact 可能较大，但页面状态需要它
            result = c.getArtifact() != null ? c.getArtifact()
                    : (c.getCompressedArtifact() != null ? c.getCompressedArtifact() : "");
        } else if (overBudget.contains(c.getId())) {
            result = RunnerConstants.TOOL_RESULT_BUDGET_OMITTED;
        } else if (ToolCallRecordStatus.SUCCEEDED.name().equals(c.getStatus())) {
            result = c.getCompressedArtifact() != null ? c.getCompressedArtifact() : "";
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
