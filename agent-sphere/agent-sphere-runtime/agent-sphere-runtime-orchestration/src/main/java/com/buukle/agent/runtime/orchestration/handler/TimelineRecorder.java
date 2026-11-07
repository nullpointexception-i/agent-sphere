package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.dto.TimelineRefQuery;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime recorder for the flattened unified Timeline index:
 * - main Agent streaming container row (created on the first token with seq backfilled, closed at run terminal state);
 * - tool rows / clarification rows / run_status rows;
 * - chat() user message rows and SkillReActExecutor sub-agent header rows (both invoked directly through SPI).
 * This class does not change existing event semantics; it only consumes their side effects to append display entries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineRecorder {

    private final AgentTimelineSpi timelineSpi;
    private final AgentPendingClarificationMapper clarificationMapper;
    private final RunSpi runSpi;
    private final AgentLlmInteractionRecordSpi interactionRecordSpi;

    private final ConcurrentHashMap<Long, Long> assistantSeqByRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolSeqByRunStep = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> clarificationSeqByRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> approvalSeqByRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> runStatusSeqByRun = new ConcurrentHashMap<>();
    /** Compaction row: the same publishId (= one compaction attempt) reuses one row, released at terminal state. */
    private final ConcurrentHashMap<String, Long> compactionSeqByPublishId = new ConcurrentHashMap<>();

    /** Run terminal display text. A successful run shows the completion time yyyy-MM-dd HH:mm:ss (no longer "task completed"). */
    private static final DateTimeFormatter RUN_TERMINAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TITLE_RUN_FAILED = "❌ Run failed";
    private static final String TITLE_RUN_CANCELLED = "⏹️ Cancelled";
    private static final String TITLE_RUN_AWAITING = "⏸️ Awaiting clarification";
    private static final String TITLE_RUN_WAITING = "⏳ Waiting (continues automatically when due)";
    private static final String TITLE_RUN_RESUMED = "▶️ Resumed (waited %s)";
    private static final String TITLE_RUN_RETRY_RESUMED = "▶️ Resumed; continuing";
    private static final String TITLE_COMPACT_RUNNING = "Compacting context…";
    private static final String TITLE_COMPACT_COMPLETED = "Context compacted";
    private static final String TITLE_COMPACT_FAILED = "Context compaction failed";

    /** Record a row directly (chat user message / sub-agent header row, etc.) and return seq. */
    public long record(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                       Long refRunId, Long refInteractionId, Long refToolCallId,
                       Long refSubAgentRunId, Long refClarificationId) {
        try {
            return timelineSpi.record(sessionId, runId, kind, subtype, state, title, refRunId,
                    refInteractionId, refToolCallId, refSubAgentRunId, refClarificationId);
        } catch (Exception e) {
            log.warn("Timeline record failed: {}", e.getMessage());
            return -1L;
        }
    }

    public void update(Long sessionId, Long seq, String state, String subtype, String title) {
        try {
            timelineSpi.update(sessionId, seq, state, subtype, title);
        } catch (Exception e) {
            log.warn("Timeline update failed: {}", e.getMessage());
        }
    }

    /** Main Agent assistant container: create the row on the first token and backfill seq (typewriter target). Returns a copy carrying seq/kind. */
    public RuntimeEventDataVO stampStreamingSeq(Long sessionId, Long runId, RuntimeEventDataVO data) {
        if (runId == null) {
            return data;
        }
        Long seq = assistantSeqByRun.get(runId);
        if (seq == null) {
            seq = openAssistantTurn(sessionId, runId);
            if (seq == null || seq <= 0) {
                return data;
            }
            assistantSeqByRun.put(runId, seq);
        }
        return data.toBuilder().seq(seq).kind(TimelineKind.ASSISTANT.getCode()).build();
    }

    /**
     * Open this turn's assistant row: turn identity comes from the execution-side (run, loop_count) interaction record.
     *
     * <p>A missing record is a hard error of the execution chain (Kernel pre-creates it before the call); there is no "create as fallback",
     * only an ERROR is logged for alerting.
     */
    private Long openAssistantTurn(Long sessionId, Long runId) {
        try {
            RunVO run = runSpi.getRun(runId);
            Integer turnNo = run == null ? null : run.getLoopCount();
            if (turnNo == null) {
                log.error("Assistant turn cannot resolve loop_count: run={}", runId);
                return null;
            }
            Long interactionId = interactionRecordSpi.findByRunAndTurn(runId, turnNo);
            if (interactionId == null) {
                log.error("Assistant turn has no interaction record: run={}, turn={}", runId, turnNo);
                return null;
            }
            return timelineSpi.recordAssistantByInteraction(sessionId, runId, interactionId);
        } catch (Exception e) {
            log.warn("Assistant turn open failed: {}", e.getMessage());
            return null;
        }
    }

    /** Run terminal state: close the main assistant container (fallback; each LLM turn is already closed by closeAssistantTurn). */
    public void closeAssistant(Long sessionId, Long runId, String state) {
        if (runId == null) {
            return;
        }
        Long seq = assistantSeqByRun.remove(runId);
        if (seq != null && seq > 0) {
            update(sessionId, seq, state, null, null);
            return;
        }
        // Restart / cross-replica: close every assistant row of this run that is still RUNNING
        try {
            timelineSpi.closeRunningAssistant(sessionId, runId, state);
        } catch (Exception e) {
            log.warn("Timeline close assistant failed: {}", e.getMessage());
        }
    }

    /** End of each LLM call (after the interaction is persisted): close the currently open assistant row and backfill this turn's reference; one row per turn. */
    public void closeAssistantTurn(Long sessionId, Long runId, String state, Long interactionId) {
        if (runId == null) {
            return;
        }
        Long seq = assistantSeqByRun.remove(runId);
        if ((seq == null || seq <= 0) && interactionId != null) {
            seq = timelineSpi.findSeqByInteraction(runId, interactionId);
        }
        if (seq == null || seq <= 0) {
            return;
        }
        update(sessionId, seq, state, null, null);
        if (interactionId != null) {
            try {
                timelineSpi.updateRefInteractionId(sessionId, seq, interactionId);
            } catch (Exception e) {
                log.warn("Timeline ref interaction failed: {}", e.getMessage());
            }
        }
    }

    /** Tool row: start/update status (main Agent tools; sub-agent tools are lazily fetched in sub-agent details and never enter the main row). */
    public void recordTool(Long sessionId, Long runId, String stepKey, Long refToolCallId, String state, String title) {
        try {
            String key = runId + ":" + stepKey;
            Long seq = toolSeqByRunStep.get(key);
            if (seq == null && runId != null && refToolCallId != null) {
                seq = findLatestSeq(TimelineRefQuery.builder()
                        .runId(runId)
                        .kind(TimelineKind.TOOL.getCode())
                        .refToolCallId(refToolCallId)
                        .build());
                if (seq != null && seq > 0) {
                    toolSeqByRunStep.put(key, seq);
                }
            }
            if (seq == null) {
                seq = timelineSpi.record(sessionId, runId, TimelineKind.TOOL.getCode(), state, state, title,
                        null, null, refToolCallId, null, null);
                toolSeqByRunStep.put(key, seq);
            } else {
                timelineSpi.update(sessionId, seq, state, state, title);
            }
        } catch (Exception e) {
            log.warn("Timeline tool row failed: {}", e.getMessage());
        }
    }

    public void removeTool(Long runId, String stepKey) {
        toolSeqByRunStep.remove(runId + ":" + stepKey);
    }

    /** Run status row: the same run reuses one row (RUNNING creates it, terminal state updates in place), avoiding stale "running" rows in history. */
    public void recordRunStatus(Long sessionId, Long runId, String state, String title) {
        if (runId == null) {
            return;
        }
        TimelineRefQuery query = TimelineRefQuery.builder()
                .runId(runId)
                .kind(TimelineKind.RUN_STATUS.getCode())
                .build();
        Long seq = runStatusSeqByRun.get(runId);
        if (seq == null) {
            seq = findLatestSeq(query);
            if (seq != null && seq > 0) {
                runStatusSeqByRun.put(runId, seq);
            }
        }
        if (seq == null) {
            seq = timelineSpi.record(sessionId, runId, TimelineKind.RUN_STATUS.getCode(), state, state, title,
                    runId, null, null, null, null);
            runStatusSeqByRun.put(runId, seq);
        } else {
            // An out-of-order RUNNING/AWAITING must not downgrade a row that is already terminal
            String latestState = findLatestState(query);
            if (isTerminalRunState(latestState) && !isTerminalRunState(state)) {
                return;
            }
            if (TimelineState.RESUMED.getCode().equals(latestState) && !isTerminalRunState(state)) {
                return;
            }
            timelineSpi.update(sessionId, seq, state, state, title);
        }
    }

    /**
     * wait resume on schedule: turn the WAITING row at the suspension point into the past tense (RESUMED),
     * so it no longer shows "waiting" after resuming. The terminal state still creates a separate row at the end of the run.
     */
    public void markWaitResumed(Long sessionId, Long runId, long waitedMs) {
        if (runId == null) {
            return;
        }
        try {
            TimelineRefQuery query = TimelineRefQuery.builder()
                    .runId(runId)
                    .kind(TimelineKind.RUN_STATUS.getCode())
                    .build();
            Long seq = runStatusSeqByRun.get(runId);
            if (seq == null) {
                seq = findLatestSeq(query);
            }
            if (seq == null || seq <= 0) {
                return;
            }
            String latestState = findLatestState(query);
            if (!TimelineState.WAITING.getCode().equals(latestState)) {
                return;
            }
            runStatusSeqByRun.put(runId, seq);
            String title = TITLE_RUN_RESUMED.formatted(formatWaitDuration(waitedMs));
            timelineSpi.update(sessionId, seq,
                    TimelineState.RESUMED.getCode(), TimelineState.RESUMED.getCode(), title);
        } catch (Exception e) {
            log.warn("Timeline wait resume row failed: {}", e.getMessage());
        }
    }

    /**
     * LLM retry resume on schedule: turn the in-place reused "retrying (k/N)" row back to "running".
     * Otherwise the row stays on the previous wording until the next suspension or the run's terminal state.
     *
     * @return the updated row; null when there is no waiting row to update
     */
    public RunStatusRow markRetryResumed(Long sessionId, Long runId) {
        if (runId == null) {
            return null;
        }
        try {
            TimelineRefQuery query = TimelineRefQuery.builder()
                    .runId(runId)
                    .kind(TimelineKind.RUN_STATUS.getCode())
                    .build();
            Long seq = runStatusSeqByRun.get(runId);
            if (seq == null) {
                seq = findLatestSeq(query);
            }
            if (seq == null || seq <= 0) {
                return null;
            }
            // Only update a "waiting" row; leave it alone if another path already updated it (terminal/cancel)
            if (!TimelineState.WAITING.getCode().equals(findLatestState(query))) {
                return null;
            }
            runStatusSeqByRun.put(runId, seq);
            timelineSpi.update(sessionId, seq, TimelineState.RUNNING.getCode(),
                    TimelineState.RUNNING.getCode(), TITLE_RUN_RETRY_RESUMED);
            return new RunStatusRow(seq, TITLE_RUN_RETRY_RESUMED);
        } catch (Exception e) {
            log.warn("Timeline retry resume row failed: {}", e.getMessage());
            return null;
        }
    }

    private static String formatWaitDuration(long ms) {
        long seconds = Math.max(1, Math.round(ms / 1000.0));
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return rest == 0 ? minutes + "m" : minutes + "m" + rest + "s";
    }

    /** (seq, title) of the run_status row: callers use it to carry "which row and what text" into the SSE event. */
    public record RunStatusRow(long seq, String title) {
    }

    /** Run terminal state: status/title mapped from RunStatus (avoids scattered magic values); updates the same row and releases this run's slot. */
    public RunStatusRow recordRunTerminal(Long sessionId, Long runId, RunStatus status) {
        return recordRunTerminal(sessionId, runId, status, null);
    }

    /**
     * @param titleOverride when non-blank, replaces the default status text (e.g. LLM retry: "busy, retrying automatically (2/6) · retry at 16:54")
     */
    public RunStatusRow recordRunTerminal(Long sessionId, Long runId, RunStatus status, String titleOverride) {
        if (runId == null) {
            return null;
        }
        String state = switch (status) {
            case COMPLETED -> TimelineState.COMPLETED.getCode();
            case FAILED -> TimelineState.FAILED.getCode();
            case CANCELLED -> TimelineState.CANCELLED.getCode();
            case AWAITING_USER -> TimelineState.AWAITING_USER.getCode();
            case WAITING -> TimelineState.WAITING.getCode();
            default -> status.name();
        };
        String title = switch (status) {
            case COMPLETED -> RUN_TERMINAL_FMT.format(LocalDateTime.now());
            case FAILED -> TITLE_RUN_FAILED;
            case CANCELLED -> TITLE_RUN_CANCELLED;
            case AWAITING_USER -> TITLE_RUN_AWAITING;
            case WAITING -> TITLE_RUN_WAITING;
            default -> throw new IllegalStateException("unexpected run status " + status);
        };
        if (titleOverride != null && !titleOverride.isBlank()) {
            title = titleOverride;
        }
        // Retry rows are marked via subtype, so the frontend keeps the WAITING row (wait's waiting row is still filtered out and rendered by WaitCard)
        String subtype = titleOverride != null && !titleOverride.isBlank()
                ? com.buukle.agent.common.constant.PendingWaitKind.LLM_RETRY
                : state;
        TimelineRefQuery query = TimelineRefQuery.builder()
                .runId(runId)
                .kind(TimelineKind.RUN_STATUS.getCode())
                .build();
        Long seq = runStatusSeqByRun.get(runId);
        if (seq == null) {
            seq = findLatestSeq(query);
            if (seq != null && seq > 0) {
                runStatusSeqByRun.put(runId, seq);
            }
        }
        boolean terminalStatus = status == RunStatus.COMPLETED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED;
        if (seq != null && seq > 0) {
            // A late pause event must not downgrade an already terminal row.
            if (isTerminalRunState(findLatestState(query)) && !terminalStatus) {
                return null;
            }
            // uk_timeline_run_status allows only one run_status row per run: every status switch updates in place.
            // The old implementation "new row at terminal state / new row after resume" hit the unique index and swallowed the insert failure,
            // so once retries were exhausted the timeline stayed on "retrying" forever (production run 2972).
            timelineSpi.update(sessionId, seq, state, subtype, title);
            if (terminalStatus) {
                runStatusSeqByRun.remove(runId, seq);
            }
            return new RunStatusRow(seq, title);
        }
        // Fallback: no status row yet (extreme path)
        long created = timelineSpi.record(sessionId, runId, TimelineKind.RUN_STATUS.getCode(),
                state, subtype, title, runId, null, null, null, null);
        if (created > 0) {
            runStatusSeqByRun.put(runId, created);
        }
        return created > 0 ? new RunStatusRow(created, title) : null;
    }

    /**
     * Compaction row: the same compaction (publishId) creates the row on RUNNING and releases it after COMPLETED/FAILED update in place;
     * the next compaction (a new publishId) creates a separate row. The body streams after {@link #stampCompactionSeq} backfills seq.
     */
    public long recordCompaction(Long sessionId, Long runId, String publishId, Long compactRecordId,
                                 String state, String detail) {
        if (sessionId == null) {
            return -1L;
        }
        String key = compactionKey(publishId, runId);
        String title = compactionTitle(state, detail);
        try {
            Long seq = compactionSeqByPublishId.get(key);
            if (seq == null && runId != null && compactRecordId != null) {
                seq = findLatestSeq(TimelineRefQuery.builder()
                        .runId(runId)
                        .kind(TimelineKind.COMPACTION.getCode())
                        .refCompactId(compactRecordId)
                        .build());
                if (seq != null && seq > 0) {
                    compactionSeqByPublishId.put(key, seq);
                }
            }
            if (seq == null) {
                seq = timelineSpi.record(sessionId, runId, TimelineKind.COMPACTION.getCode(), state, state, title,
                        runId, null, null, null, null);
                if (compactRecordId != null) {
                    timelineSpi.updateRefCompactId(sessionId, seq, compactRecordId);
                }
                compactionSeqByPublishId.put(key, seq);
            } else {
                timelineSpi.update(sessionId, seq, state, state, title);
            }
            if (isCompactionTerminal(state)) {
                compactionSeqByPublishId.remove(key);
            }
            return seq;
        } catch (Exception e) {
            log.warn("Timeline compaction row failed: {}", e.getMessage());
            return -1L;
        }
    }

    /** Streaming compaction-output token: backfill seq/kind by publishId (the row should already exist from the RUNNING event). Returns a copy. */
    public RuntimeEventDataVO stampCompactionSeq(RuntimeEventDataVO data) {
        Long runId = data.getRunId();
        String key = compactionKey(data.getPublishId(), runId);
        Long seq = compactionSeqByPublishId.get(key);
        if (seq == null && data.getSessionId() != null) {
            seq = recordCompaction(data.getSessionId(), runId, data.getPublishId(), data.getCompactRecordId(),
                    TimelineState.RUNNING.getCode(), null);
        }
        if (seq != null && seq > 0) {
            return data.toBuilder().seq(seq).kind(TimelineKind.COMPACTION.getCode()).build();
        }
        return data;
    }

    private static String compactionKey(String publishId, Long runId) {
        return publishId != null && !publishId.isBlank() ? publishId : "run:" + runId;
    }

    private static boolean isCompactionTerminal(String state) {
        return TimelineState.COMPLETED.getCode().equals(state) || TimelineState.FAILED.getCode().equals(state);
    }

    private Long findLatestSeq(TimelineRefQuery query) {
        try {
            Long seq = timelineSpi.findLatestSeq(query);
            // Mockito/exception fallbacks may return 0; accept only positive seq, so 0 is never treated as an existing row to update
            return seq != null && seq > 0 ? seq : null;
        } catch (Exception e) {
            log.warn("Timeline findLatestSeq failed: {}", e.getMessage());
            return null;
        }
    }

    private String findLatestState(TimelineRefQuery query) {
        try {
            return timelineSpi.findLatestState(query);
        } catch (Exception e) {
            log.warn("Timeline findLatestState failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isTerminalRunState(String state) {
        return TimelineState.COMPLETED.getCode().equals(state)
                || TimelineState.FAILED.getCode().equals(state)
                || TimelineState.CANCELLED.getCode().equals(state);
    }

    private static boolean isTerminalClarificationState(String state) {
        return TimelineState.ANSWERED.getCode().equals(state)
                || TimelineState.CANCELLED.getCode().equals(state);
    }

    private static boolean isTerminalApprovalState(String state) {
        return TimelineState.ANSWERED.getCode().equals(state)
                || TimelineState.CANCELLED.getCode().equals(state);
    }

    static String compactionTitle(String state, String detail) {
        String base = TITLE_COMPACT_RUNNING;
        if (TimelineState.COMPLETED.getCode().equals(state)) {
            base = TITLE_COMPACT_COMPLETED;
        } else if (TimelineState.FAILED.getCode().equals(state)) {
            base = TITLE_COMPACT_FAILED;
        }
        return detail != null && !detail.isBlank() ? base + " · " + detail : base;
    }

    /** Clarification row: pending→answered reuses the same row. */
    public void recordClarification(Long sessionId, Long runId, String clarificationId, String state, String title) {
        if (runId == null) {
            return;
        }
        try {
            String key = runId + ":" + clarificationId;
            Long seq = clarificationSeqByRun.get(key);
            Long refId = lookupClarificationRef(runId, clarificationId);
            if (seq == null && refId != null) {
                // Map lost after restart/cross-replica: look up the old row for the same ref in the DB instead of leaving a new PENDING row behind
                seq = findLatestSeq(TimelineRefQuery.builder()
                        .runId(runId)
                        .kind(TimelineKind.CLARIFICATION.getCode())
                        .refClarificationId(refId)
                        .build());
                if (seq != null && seq > 0) {
                    clarificationSeqByRun.put(key, seq);
                }
            }
            if (seq == null) {
                seq = timelineSpi.record(sessionId, runId, TimelineKind.CLARIFICATION.getCode(), state, state, title,
                        runId, null, null, null, refId);
                clarificationSeqByRun.put(key, seq);
            } else {
                // subtype must change together with state: the old implementation passed only state, leaving the PENDING subtype behind
                timelineSpi.update(sessionId, seq, state, state, title);
            }
            if (isTerminalClarificationState(state)) {
                clarificationSeqByRun.remove(key);
            }
        } catch (Exception e) {
            log.warn("Timeline clarification row failed: {}", e.getMessage());
        }
    }

    public void recordToolApproval(Long sessionId, Long runId, String approvalId, Long refApprovalId,
                                   String state, String title) {
        if (runId == null || approvalId == null || approvalId.isBlank()) {
            return;
        }
        try {
            String key = runId + ":" + approvalId;
            Long seq = approvalSeqByRun.get(key);
            if (seq == null && refApprovalId != null) {
                seq = findLatestSeq(TimelineRefQuery.builder()
                        .runId(runId)
                        .kind(TimelineKind.TOOL_APPROVAL.getCode())
                        .refApprovalId(refApprovalId)
                        .build());
                if (seq != null && seq > 0) {
                    approvalSeqByRun.put(key, seq);
                }
            }
            if (seq == null) {
                seq = timelineSpi.recordApproval(sessionId, runId, TimelineKind.TOOL_APPROVAL.getCode(),
                        state, state, title, runId, refApprovalId);
                approvalSeqByRun.put(key, seq);
            } else {
                timelineSpi.update(sessionId, seq, state, state, title);
            }
            if (isTerminalApprovalState(state)) {
                approvalSeqByRun.remove(key);
            }
        } catch (Exception e) {
            log.warn("Timeline tool approval row failed: {}", e.getMessage());
        }
    }

    private Long lookupClarificationRef(Long runId, String clarificationId) {
        try {
            AgentPendingClarification c = clarificationMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentPendingClarification>()
                            .eq(AgentPendingClarification::getRunId, runId)
                            .eq(AgentPendingClarification::getClarificationId, clarificationId)
                            .last("LIMIT 1"));
            return c != null ? c.getId() : null;
        } catch (Exception e) {
            return null;
        }
    }
}