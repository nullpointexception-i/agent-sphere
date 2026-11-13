package com.buukle.agent.runtime.kernel.loader;

import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.ProjectContextSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.runner.tool.LiveToolResultPolicy;
import com.buukle.agent.runtime.kernel.util.ScreenshotObservationSupport;
import com.buukle.agent.util.json.JsonUtils;
import com.buukle.agent.util.json.ToolCallReason;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryLoader {

    /** Marker of the run-local compaction summary (leading system; each compaction replaces the old copy). */
    public static final String RUN_SUMMARY_PREFIX = "\n\n[Current run summary]\n";

    private static final TypeReference<List<ChatMessageDTO>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };

    private final RunSpi runSpi;
    private final AgentCompactRecordSpi compactRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final AgentLlmInteractionRecordSpi interactionSpi;
    private final AgentRuntimeProperties agentRuntimeProperties;
    private final com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver attachmentResolver;
    /**
     * Optional so the loader can still be constructed in isolation (tests / preview paths);
     * the runtime context always provides them.
     */
    @Autowired
    private ObjectProvider<SessionSpi> sessionSpiProvider;
    @Autowired
    private ObjectProvider<ProjectContextSpi> projectContextSpi;

    /** A fork may chain only a couple of levels; the canonical session has no parent. */
    private static final int MAX_FORK_DEPTH = 3;

    /** Messages + the start index of the current run (-1 = the current run was not loaded this time). */
    public record HistoryResult(List<ChatMessageDTO> messages, int currentRunStart) {
    }

    public List<ChatMessageDTO> load(Long sessionId, Long excludeRunId) {
        return loadResult(sessionId, excludeRunId, false).messages();
    }

    public List<ChatMessageDTO> load(Long sessionId, Long excludeRunId, boolean restoreCurrentRun) {
        return loadResult(sessionId, excludeRunId, restoreCurrentRun).messages();
    }

    /**
     * @param restoreCurrentRun true = wait/clarification resume: restore the current run's context (prefer run-local summary+tail,
     *                           otherwise rebuild from persisted tool history + per-loop assistant bodies).
     *                          false = new run / session compaction reassembly: the caller keeps the current run's tail,
     *                          and only the run-local summary prefix is added here, without replaying the tail.
     */
    public HistoryResult loadResult(Long sessionId, Long excludeRunId, boolean restoreCurrentRun) {
        return loadResultInternal(sessionId, excludeRunId, restoreCurrentRun, 0);
    }

    private HistoryResult loadResultInternal(Long sessionId, Long excludeRunId,
                                             boolean restoreCurrentRun, int depth) {
        List<ChatMessageDTO> messages = new ArrayList<>();

        // A user fork starts from the canonical project-assistant session's history,
        // then adds its own runs. The shared project context is injected only once below.
        appendForkParentHistory(messages, sessionId, depth);
        if (depth == 0) {
            appendProjectContext(messages, sessionId);
        }

        AgentCompactRecordVO latestCompact = compactRecordSpi.getLatestCompleted(sessionId);
        appendSessionSummary(messages, latestCompact);
        Long afterRunId = latestCompact != null && latestCompact.getCompactedUptoRunId() != null
                ? latestCompact.getCompactedUptoRunId() : 0L;
        List<RunVO> runs = runSpi.listRunsBySessionAfterId(sessionId, afterRunId);

        RunLocalSummary runLocal = appendRunLocalSummary(messages, excludeRunId, restoreCurrentRun);
        appendCancelledRunHint(messages, runs, excludeRunId);

        boolean includeCurrent = restoreCurrentRun && !runLocal.hasSummary() && excludeRunId != null;
        Map<Long, List<AgentToolCallRecordVO>> callsByRun =
                collectToolCallsByRun(sessionId, runs, excludeRunId, includeCurrent, restoreCurrentRun);
        Set<Long> overBudget = markOverBudget(runs, callsByRun);

        // Build completed runs chronologically (current run handled separately).
        for (RunVO run : runs) {
            if (excludeRunId != null && excludeRunId.equals(run.getId())) {
                continue;
            }
            appendRun(messages, run, callsByRun.get(run.getId()), overBudget);
        }

        RunVO currentRun = runs.stream()
                .filter(r -> excludeRunId != null && excludeRunId.equals(r.getId()))
                .findFirst().orElse(null);
        int currentRunStart = appendCurrentRun(messages, currentRun, excludeRunId, callsByRun, overBudget,
                includeCurrent, restoreCurrentRun, runLocal);
        return new HistoryResult(messages, currentRunStart);
    }

    private void appendSessionSummary(List<ChatMessageDTO> messages, AgentCompactRecordVO latestCompact) {
        if (latestCompact == null || latestCompact.getSummaryAfter() == null
                || latestCompact.getSummaryAfter().isBlank()) {
            return;
        }
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.SYSTEM)
                .setContent(RunnerConstants.HISTORY_SUMMARY_PREFIX + latestCompact.getSummaryAfter()));
    }

    /** Prepend the canonical project-assistant history to a user fork. */
    private void appendForkParentHistory(List<ChatMessageDTO> messages, Long sessionId, int depth) {
        if (depth >= MAX_FORK_DEPTH) {
            return;
        }
        SessionVO session = safeSession(sessionId);
        if (session == null || session.getParentSessionId() == null) {
            return;
        }
        HistoryResult parent = loadResultInternal(
                session.getParentSessionId(), null, false, depth + 1);
        messages.addAll(parent.messages());
    }

    private SessionVO safeSession(Long sessionId) {
        if (sessionId == null || sessionSpiProvider == null) {
            return null;
        }
        SessionSpi sessionSpi = sessionSpiProvider.getIfAvailable();
        if (sessionSpi == null) {
            return null;
        }
        try {
            return sessionSpi.getSessionShared(sessionId);
        } catch (Exception e) {
            log.debug("History parent session {} not readable: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** Shared project context: project/requirement changes, comments, and assistant writes. */
    private void appendProjectContext(List<ChatMessageDTO> messages, Long sessionId) {
        if (projectContextSpi == null) {
            return;
        }
        ProjectContextSpi spi = projectContextSpi.getIfAvailable();
        if (spi == null) {
            return;
        }
        try {
            String context = spi.buildContext(sessionId);
            if (context != null && !context.isBlank()) {
                messages.add(new ChatMessageDTO()
                        .setRole(LlmRoles.SYSTEM)
                        .setContent(context));
            }
        } catch (Exception e) {
            log.warn("Project context load failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Run-local compaction of the current run: the summary always acts as the leading system;
     * the tail is injected only on resume, because during session compaction reassembly the
     * caller keeps its own {@code currentRunTail} to avoid duplication.
     */
    private RunLocalSummary appendRunLocalSummary(List<ChatMessageDTO> messages, Long excludeRunId,
                                                  boolean restoreCurrentRun) {
        AgentCompactRecordVO runCompact = excludeRunId != null
                ? compactRecordSpi.getLatestCompletedByRunId(excludeRunId) : null;
        boolean hasSummary = runCompact != null && runCompact.getSummaryAfter() != null
                && !runCompact.getSummaryAfter().isBlank();
        if (!hasSummary) {
            return RunLocalSummary.absent();
        }
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.SYSTEM)
                .setContent(RUN_SUMMARY_PREFIX + runCompact.getSummaryAfter()));
        List<ChatMessageDTO> runTail = restoreCurrentRun ? parseTail(runCompact.getTailJson()) : null;
        return new RunLocalSummary(true, runTail);
    }

    private void appendCancelledRunHint(List<ChatMessageDTO> messages, List<RunVO> runs, Long excludeRunId) {
        // Detect if any previous run was cancelled — inject a context hint.
        List<RunVO> cancelled = runs.stream()
                .filter(r -> RunStatus.CANCELLED.name().equals(r.getStatus()))
                .filter(r -> excludeRunId == null || !excludeRunId.equals(r.getId()))
                .toList();
        if (cancelled.isEmpty()) {
            return;
        }
        String originalUserMessage = cancelled.stream()
                .map(RunVO::getUserMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .findFirst().orElse("");
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.SYSTEM)
                .setContent("[The previous run was cancelled. "
                        + (originalUserMessage.isBlank()
                        ? "The user may be continuing their original request."
                        : "The user's original request was: \"" + originalUserMessage + "\". Read history carefully and continue.")
                        + " Do NOT ask 'what do you want to do' — execute the task.]"));
    }

    private Map<Long, List<AgentToolCallRecordVO>> collectToolCallsByRun(Long sessionId, List<RunVO> runs,
                                                                        Long excludeRunId, boolean includeCurrent,
                                                                        boolean restoreCurrentRun) {
        Map<Long, List<AgentToolCallRecordVO>> callsByRun = new LinkedHashMap<>();
        for (RunVO run : runs) {
            boolean current = excludeRunId != null && excludeRunId.equals(run.getId());
            // If the run-local tail is corrupt and a fallback is needed, also read the current run's tool records.
            if (current && !includeCurrent && !restoreCurrentRun) {
                continue;
            }
            List<AgentToolCallRecordVO> calls =
                    new ArrayList<>(toolCallRecordSpi.listBySessionId(sessionId, run.getId()));
            // A sub-agent's internal tool calls are not part of the parent run's conversation history: the parent agent sees the delegate result only once,
            // and replaying them would let the model see tool_calls it never issued (with different turn_no semantics).
            calls.removeIf(c -> c.getSubAgentRunId() != null);
            Collections.reverse(calls);
            if (!calls.isEmpty()) {
                callsByRun.put(run.getId(), calls);
            }
        }
        return callsByRun;
    }

    /** Accumulates from newest to oldest and marks entries that exceed the tool-result history budget. */
    private Set<Long> markOverBudget(List<RunVO> runs, Map<Long, List<AgentToolCallRecordVO>> callsByRun) {
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
        return overBudget;
    }

    /**
     * Appends the current run on resume: prefer the run-local tail, otherwise replay persisted
     * tool history plus per-loop assistant bodies.
     *
     * @return index where the current run starts, or -1 when it was not appended
     */
    private int appendCurrentRun(List<ChatMessageDTO> messages, RunVO currentRun, Long excludeRunId,
                                 Map<Long, List<AgentToolCallRecordVO>> callsByRun, Set<Long> overBudget,
                                 boolean includeCurrent, boolean restoreCurrentRun, RunLocalSummary runLocal) {
        if (includeCurrent && currentRun != null) {
            int start = messages.size();
            appendRunWithAssistantText(messages, currentRun, callsByRun.get(currentRun.getId()), overBudget,
                    restoreCurrentRun);
            return start;
        }
        if (!restoreCurrentRun || !runLocal.hasSummary()) {
            return -1;
        }
        if (runLocal.tail() != null && !runLocal.tail().isEmpty()) {
            int start = messages.size();
            messages.addAll(runLocal.tail());
            return start;
        }
        log.warn("Run-local compaction tail unreadable for run {}; falling back to persisted tool history",
                excludeRunId);
        if (currentRun == null) {
            return -1;
        }
        int start = messages.size();
        appendRunWithAssistantText(messages, currentRun, callsByRun.get(currentRun.getId()), overBudget,
                restoreCurrentRun);
        return start;
    }

    /** Presence of the run-local summary plus the optional resume tail. */
    private record RunLocalSummary(boolean hasSummary, List<ChatMessageDTO> tail) {
        static RunLocalSummary absent() {
            return new RunLocalSummary(false, null);
        }
    }

    private void appendRun(List<ChatMessageDTO> messages, RunVO run,
                           List<AgentToolCallRecordVO> calls, Set<Long> overBudget) {
        appendRunWithAssistantText(messages, run, calls, overBudget, false);
    }

    /**
     * History reconstruction for a run: tool records are paired in time order with each loop's CHAT_REPLY and the assistant body comes from reply_content;
     * reasoning is written only to assistant.reasoning_content, never to the body. Falls back to plain tool record reconstruction when pairing fails.
     *
     * @param replayScreenshots true only when resuming the current run: re-inject recent screenshot observation messages from artifacts
     */
    private void appendRunWithAssistantText(List<ChatMessageDTO> messages, RunVO run,
                                            List<AgentToolCallRecordVO> calls,
                                            Set<Long> overBudget, boolean replayScreenshots) {
        int start = messages.size();
        appendRunWithAssistantTextAttempt(messages, run, calls, overBudget, replayScreenshots);
        // Structural self-check: assistant(tool_calls) must be followed directly by tool messages covering all tool_call_ids.
        // Otherwise the model endpoint returns 400 (insufficient tool messages following tool_calls),
        // so here we would rather degrade to a plain "one assistant + one tool" reconstruction than send an illegal sequence.
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
                    .setRole(LlmRoles.USER)
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
        // When turn_no is present, group exactly by loop and drop the time window entirely; legacy data (before the migration) falls back to the millisecond window.
        // A sub-agent's tool records have a null turn_no and land in the fallback branch, behaving as before the change.
        Map<Integer, List<AgentToolCallRecordVO>> callsByTurn = indexByTurn(allCalls);
        boolean turnKeyed = callsByTurn != null
                && interactions.stream().allMatch(x -> x.getTurnNo() != null)
                // When a loop has multiple records (left over from historical attempts) they cannot be matched one-to-one, so fall back to the time window
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
                ChatMessageDTO assistant = new ChatMessageDTO().setRole(LlmRoles.ASSISTANT);
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
                // Append all tool results of the loop first, then inject screenshot observations uniformly: this guarantees
                // assistant(tool_calls) → tool* contiguity (multi-tool batches are not interrupted) and matches the live path.
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
        // Tool records written after the last interaction (for example the persisted wait row) have no next loop to pair with; append an assistant(tool_calls)
        List<AgentToolCallRecordVO> remaining = turnKeyed
                ? callsByTurn.values().stream().flatMap(List::stream).toList()
                : (idx < allCalls.size() ? allCalls.subList(idx, allCalls.size()) : List.of());
        if (!remaining.isEmpty()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmRoles.ASSISTANT)
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
     * Groups tool records by turn_no; if any record lacks turn_no (legacy rows before the migration / sub-agent internal calls)
     * this returns null and lets the caller fall back to the time window entirely — mixing both pairing schemes half-and-half would misalign more subtly.
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
     * Fallback reconstruction when pairing fails / the structure is illegal: a plain sequence of one assistant + one tool,
     * with screenshot observations always placed at the end and never inserted between tool messages.
     */
    private void appendToolCallFallback(List<ChatMessageDTO> messages, RunVO run,
                                        List<AgentToolCallRecordVO> calls,
                                        Set<Long> overBudget, boolean replayScreenshots) {
        if (run.getUserMessage() != null && !run.getUserMessage().isBlank()) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmRoles.USER)
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
     * Structural self-check: every assistant with tool_calls must be followed directly by contiguous tool messages
     * that cover all tool_call_ids it declares (a hard constraint of OpenAI/DeepSeek).
     */
    static boolean hasValidToolCallPairing(List<ChatMessageDTO> segment) {
        for (int i = 0; i < segment.size(); i++) {
            ChatMessageDTO message = segment.get(i);
            if (!LlmRoles.ASSISTANT.equals(message.getRole())
                    || message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
                continue;
            }
            Set<String> expected = new HashSet<>();
            for (ToolCallDTO toolCall : message.getToolCalls()) {
                expected.add(toolCall.getId());
            }
            int j = i + 1;
            while (j < segment.size() && !expected.isEmpty()
                    && LlmRoles.TOOL.equals(segment.get(j).getRole())) {
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
        // Take only the most recent N: screenshots are large and there is no need to stuff every historical page back in on resume
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

    /** Byte budget for screenshot replay during resume (valid within a single HistoryLoader call). */
    private static final class ScreenshotReplayBudget {
        private final int maxBytes;
        private int usedBytes;
        private boolean exhausted;

        private ScreenshotReplayBudget(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    private void addFinalAssistantReply(List<ChatMessageDTO> messages, RunVO run) {
        // Usually empty for a running run; fill it once only when there is a real terminal body and no per-loop body at all, to avoid duplication.
        if (run.getAssistantReply() == null || run.getAssistantReply().isBlank()) {
            return;
        }
        boolean hasAssistantText = messages.stream().anyMatch(m ->
                LlmRoles.ASSISTANT.equals(m.getRole())
                        && m.getContent() instanceof String s && !s.isBlank());
        if (!hasAssistantText) {
            messages.add(new ChatMessageDTO()
                    .setRole(LlmRoles.ASSISTANT)
                    .setContent(run.getAssistantReply()));
        }
    }

    /**
     * Decides whether a tool record falls inside a loop's CHAT_REPLY time window.
     *
     * <p>Comparison must use milliseconds: the VO's createdAt is a second-precision string, while "the call lands in the same second the next loop starts"
     * really happens (production run 2810: tool 03:54:51.000042 vs next loop 03:54:51.842411).
     * Comparing by second would mismatch this call into the next loop, leaving one assistant with two tool_calls.
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
        // Fallback: legacy data / unpopulated millisecond values fall back to second-precision string comparison (still the previous behavior)
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
                    .setRole(LlmRoles.ASSISTANT)
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
                .setRole(LlmRoles.TOOL)
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
