package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentUserInLoopRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.AgentUserInLoopRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalSpi;
import com.buukle.agent.runtime.kernel.ReasoningBufferStore;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.runner.LiveToolResultPolicy;
import com.buukle.agent.runtime.kernel.port.vo.*;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import com.buukle.agent.runtime.orchestration.chrome.ChromeCommandEnvelope;
import com.buukle.agent.runtime.orchestration.chrome.ChromeCommandEvent;
import com.buukle.agent.runtime.orchestration.sse.SseEventCache;
import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class RuntimeEventListener {
    private final SseEventCache eventCache;
    private final RedisEventBus eventBus;
    private final RunSpi runSpi;
    private final AgentUserInLoopRecordSpi hitlRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final SessionSpi sessionSpi;
    private final AgentRuntimeProperties runtimeProperties;
    private final ReasoningBufferStore reasoningBufferStore;
    private final TimelineRecorder timelineRecorder;
    private final ToolApprovalSpi toolApprovalSpi;

    private static RuntimeEventVO transformForFrontend(RuntimeEventVO event, RuntimeEventDataVO data) {
        EventType type = event.getEventType();
        return switch (type) {
            case ToolCallStatus t -> wrapReasoning(event, data, toolCallSubType(t));
            case CompactionStatus c -> wrapReasoning(event, data, compactSubType(c));
            case SessionStatus s -> wrapReasoning(event, data, sessionSubType(s));
            case RunStatus r -> wrapReasoning(event, data, runSubType(r));
            default -> new RuntimeEventVO(type, data);
        };
    }

    private static String toolCallSubType(ToolCallStatus t) {
        return switch (t) {
            case PENDING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_STARTED;
            case RUNNING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_IN_PROGRESS;
            case SUCCEEDED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_SUCCEEDED;
            case FAILED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_FAILED;
        };
    }

    private static String compactSubType(CompactionStatus c) {
        return switch (c) {
            case PENDING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_RUNNING;
            case RUNNING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_RUNNING;
            case COMPLETED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_COMPLETED;
            case FAILED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_FAILED;
        };
    }

    private static String sessionSubType(SessionStatus s) {
        return switch (s) {
            case TITLE_UPDATED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_SESSION_UPDATED;
        };
    }

    private static String runSubType(RunStatus r) {
        return switch (r) {
            case PENDING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_PENDING;
            case RUNNING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_RUNNING;
            case COMPLETED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_COMPLETED;
            case FAILED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_FAILED;
            case CANCELLED -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_CANCELLED;
            case AWAITING_USER -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_AWAITING_USER;
            case WAITING -> RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_WAITING;
        };
    }

    private static RuntimeEventVO wrapReasoning(RuntimeEventVO event, RuntimeEventDataVO src, String subType) {
        String name = src.getDisplayNameCn() != null ? src.getDisplayNameCn()
                : (src.getToolName() != null ? src.getToolName() : "");
        String text = switch (subType) {
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_STARTED -> "⚙️ **" + name + "**: starting...";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_IN_PROGRESS -> "⚙️ **" + name + "**: calling...";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_SUCCEEDED -> "⚙️ **" + name + "**: succeeded ✅";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_CALL_FAILED -> "⚙️ **" + name + "**: failed ❌";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_RUNNING -> "\n🔄 ------Compacting------\n";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_COMPLETED -> "\n🔄 Compaction done\n";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_FAILED -> "\n🔄 Compaction failed ❌\n";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_SESSION_UPDATED -> "📝 Title updated";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_PENDING -> "▶️ Run starting...";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_RUNNING -> "▶️ Run running";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_COMPLETED -> "✅ Run completed";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_FAILED -> "❌ Run failed";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_CANCELLED -> "⏹️ Run cancelled";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_AWAITING_USER -> "⏸️ Awaiting user confirmation";
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_WAITING ->
                    src.getAssistantReply() != null ? src.getAssistantReply() : "⏳ Waiting";
            default -> "";
        };
        RuntimeEventDataVO dst = RuntimeEventDataVO.builder()
                .sessionId(src.getSessionId())
                .runId(src.getRunId())
                .publishId(src.getPublishId())
                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                .reasoningSubType(subType)
                .assistantReply(src.getAssistantReply())
                .statusTitle(src.getStatusTitle())
                .artifact(src.getArtifact())
                .argumentsJson(src.getArgumentsJson())
                .errorMessage(src.getErrorMessage())
                .subAgentRunId(src.getSubAgentRunId())
                .nodeName(src.getNodeName())
                .seq(src.getSeq())
                .kind(src.getKind())
                .type(src.getType())
                .clarificationId(src.getClarificationId())
                .multiple(src.getMultiple())
                .prompt(src.getPrompt())
                // Retry/status-row metadata: the frontend updates the run_status row in place from this, without polling again
                .waitKind(src.getWaitKind())
                .retryAttempt(src.getRetryAttempt())
                .retryMax(src.getRetryMax())
                .nextRetryAt(src.getNextRetryAt())
                .retryHint(src.getRetryHint())
                .retryHttpStatus(src.getRetryHttpStatus())
                .status(src.getStatus())
                .displayNameCn(name)
                .displayNameEn(src.getDisplayNameEn())
                .toolName(src.getToolName())
                .response(text)
                .build();

        return new RuntimeEventVO(FlowEventType.REASONING_TOKEN, dst);
    }

    @EventListener
    public void onEvent(RuntimeEventVO event) {
        EventType eventType = event.getEventType();
        RuntimeEventDataVO data = event.getData();

        switch (eventType) {
            case RunStatus r -> data = handleRunLifecycle(data, r);
            case ToolCallStatus s -> handleToolCallLifecycle(data, s);
            case UserInLoopRecordStatus s -> handleUserInLoopLifecycle(data, s);
            case CompactionStatus s -> data = handleCompaction(data, s);
            case SessionStatus s -> handleSessionLifecycle(data, s);
            case FlowEventType f -> data = handleFlow(data, f);
            case ClarificationStatus s -> handleClarification(data, s);
            case ToolApprovalStatus s -> handleToolApproval(data, s);
            // Chrome commands now travel as their own record event (ChromeCommandEvent).
            case ChromeCommandEventType ignored -> {
            }
        }

        Long sessionId = data != null ? data.getSessionId() : null;
        if (sessionId != null) {
            RuntimeEventVO frontendEvent = transformForFrontend(event, data);
            // Clear the session cache when a new run starts, so reconnects do not replay old run events
            if (event.getEventType() instanceof RunStatus s && s == RunStatus.PENDING) {
                eventCache.clearSessionEvents(sessionId);
            }
            // Single writer (this executing replica): append to the cache before publishing, so cross-replica reconnects replay without duplicates
            eventCache.appendSessionEvent(sessionId, frontendEvent);
            eventBus.publish(DistributedRuntimeConstants.TOPIC_EVENTS, frontendEvent);
        }
    }

    /** Browser command: deliver to the user-level task connection (browser extension) of the user owning the session, broadcast through the event bus. */
    @EventListener
    public void onChromeCommand(ChromeCommandEvent chromeEvent) {
        Long sessionId = chromeEvent.sessionId();
        if (sessionId == null) {
            return;
        }
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session != null && session.getCreatedBy() != null && !session.getCreatedBy().isBlank()) {
                eventBus.publish(DistributedRuntimeConstants.TOPIC_CHROME_COMMAND,
                        new ChromeCommandEnvelope(session.getCreatedBy(), chromeEvent.command()));
            }
        } catch (Exception e) {
            // Session missing / no owner: frontend delivery is session-scoped only; ignore
        }
    }

    private RuntimeEventDataVO handleRunLifecycle(RuntimeEventDataVO data, RunStatus status) {
        if (data.getRunId() == null) return data;
        RunVO run = runSpi.getRun(data.getRunId());
        if (run == null) return data;
        switch (status) {
            case PENDING, RUNNING -> {
                // Conditional update: a terminal run must not be pulled back to running by a late PENDING/RUNNING event
                runSpi.updateStatusIfActive(run.getId(), status.value());
            }
            case COMPLETED -> {
                run.setStatus(status.value());
                if (data.getType() != null) run.setType(data.getType());
                if (data.getAssistantReply() != null) run.setAssistantReply(data.getAssistantReply());
                flushReasoning(run);
                runSpi.updateRun(run);
                timelineRecorder.closeAssistant(data.getSessionId(), run.getId(), TimelineState.COMPLETED.getCode());
                data = stampRunStatus(data, timelineRecorder.recordRunTerminal(
                        data.getSessionId(), run.getId(), status), status);
            }
            case FAILED -> {
                run.setStatus(status.value());
                // Failure reason must be persisted (V87): this used to write only status and drop the
                // event's errorMessage, while the run table had no matching column — a dead run left
                // no reason at all and could not be diagnosed afterwards.
                // Every FAILED publisher (SessionRunner single-turn failure / RuntimeOrchestrator submit-time failure /
                // OrphanRunSweeper / failStuckWait) already puts the reason in the event; this is the only finalization point.
                if (data.getErrorMessage() != null && !data.getErrorMessage().isBlank()) {
                    run.setErrorMessage(data.getErrorMessage());
                }
                flushReasoning(run);
                runSpi.updateRun(run);
                timelineRecorder.closeAssistant(data.getSessionId(), run.getId(), TimelineState.FAILED.getCode());
                data = stampRunStatus(data, timelineRecorder.recordRunTerminal(
                        data.getSessionId(), run.getId(), status), status);
            }
            case CANCELLED -> {
                run.setStatus(status.value());
                flushReasoning(run);
                runSpi.updateRun(run);
                timelineRecorder.closeAssistant(data.getSessionId(), run.getId(), TimelineState.CANCELLED.getCode());
                data = stampRunStatus(data, timelineRecorder.recordRunTerminal(
                        data.getSessionId(), run.getId(), status), status);
            }
            case AWAITING_USER -> {
                // Conditional update: a cancelled/terminal run must not be downgraded by a late pause event
                runSpi.updateStatusIfActive(run.getId(), status.value());
                data = stampRunStatus(data, timelineRecorder.recordRunTerminal(
                        data.getSessionId(), run.getId(), status), status);
            }
            case WAITING -> {
                // wait suspension: a parked state like AWAITING_USER — update the run status and record a "waiting" timeline row;
                // when the same run resumes later, the row is updated in place.
                runSpi.updateStatusIfActive(run.getId(), status.value());
                data = stampRunStatus(data, timelineRecorder.recordRunTerminal(data.getSessionId(), run.getId(), status,
                        retryTitle(data)), status);
            }
        }
        return data;
    }

    /** Waiting-row text for an LLM retry; returns null for non-retry suspensions (falls back to the default "waiting"). */
    private static String retryTitle(RuntimeEventDataVO data) {
        if (!com.buukle.agent.common.constant.PendingWaitKind.LLM_RETRY.equals(data.getWaitKind())) {
            return null;
        }
        String hint = switch (String.valueOf(data.getRetryHint())) {
            case com.buukle.agent.runtime.kernel.runner.LlmRetryPolicy.HINT_PROVIDER_BUSY -> "Upstream model service is busy";
            case com.buukle.agent.runtime.kernel.runner.LlmRetryPolicy.HINT_CONNECT_TIMEOUT -> "Timed out connecting to the model service";
            case com.buukle.agent.runtime.kernel.runner.LlmRetryPolicy.HINT_RATE_LIMITED -> "Too many requests";
            default -> "Model call failed";
        };
        StringBuilder sb = new StringBuilder("⚠ ").append(hint).append(", retrying automatically");
        if (data.getRetryAttempt() != null && data.getRetryMax() != null) {
            sb.append(" (").append(data.getRetryAttempt()).append("/").append(data.getRetryMax()).append(")");
        }
        if (data.getNextRetryAt() != null) {
            sb.append(" · ")
                    .append(java.time.LocalDateTime.ofInstant(
                                    java.time.Instant.ofEpochMilli(data.getNextRetryAt()),
                                    java.time.ZoneId.of("Asia/Shanghai"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                    .append(" retry");
        }
        if (data.getRetryHttpStatus() != null) {
            sb.append(" · HTTP ").append(data.getRetryHttpStatus());
        }
        return sb.toString();
    }

    /** Carry the run_status row's seq/title/state back on the SSE event: the frontend no longer has to blindly wait for an afterSeq refresh. */
    /** Carry the run_status row's seq/title/state back on the SSE event: the frontend no longer has to blindly wait for an afterSeq refresh. */
    private static RuntimeEventDataVO stampRunStatus(RuntimeEventDataVO data,
                                                     TimelineRecorder.RunStatusRow row,
                                                     RunStatus status) {
        var builder = data.toBuilder().status(status.value());
        if (row != null && row.seq() > 0) {
            builder.seq(row.seq())
                    .kind(com.buukle.agent.instance.dtvo.enums.TimelineKind.RUN_STATUS.getCode())
                    // Use a dedicated field for the row title; do not overwrite assistantReply (the content)
                    .statusTitle(row.title());
        }
        return builder.build();
    }

    /** Accumulate this run's model reasoning (thinking) text: llm type only, excluding system status rows; written to the run once at terminal state.
     *  Also: the main Agent's first token opens the unified Timeline assistant container row and backfills seq (typewriter target).
     *  Returns a copy carrying seq/kind (the event object itself is immutable). */
    private RuntimeEventDataVO handleFlow(RuntimeEventDataVO data, FlowEventType flow) {
        Long runId = data.getRunId();
        if (runId == null) {
            return data;
        }
        boolean isReasoning = flow == FlowEventType.REASONING_TOKEN;
        boolean isContent = flow == FlowEventType.CONTENT_TOKEN;
        if (!isReasoning && !isContent) {
            return data;
        }
        // Streaming output of the compaction model: goes to the compaction row and must never land in the assistant container (it would pollute this turn's reply/reasoning).
        if (isContent && RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_OUTPUT
                .equals(data.getReasoningSubType())) {
            return timelineRecorder.stampCompactionSeq(data);
        }
        // Main Agent streaming container: main agent only (sub-agents and their header rows are managed by SkillReActExecutor, subscribed live per sub_agent_run_id inside the card)
        if (data.getSubAgentRunId() == null
                && (isContent || RuntimeEventTypeConstant.REASONING_TYPE_LLM.equals(data.getReasoningType()))) {
            data = timelineRecorder.stampStreamingSeq(data.getSessionId(), runId, data);
        }
        if (!isReasoning) {
            return data;
        }
        String response = data.getResponse();
        if (response == null || response.isBlank()) {
            return data;
        }
        if (!RuntimeEventTypeConstant.REASONING_TYPE_LLM.equals(data.getReasoningType())) {
            return data;
        }
        reasoningBufferStore.accumulate(runId, response);
        return data;
    }

    /**
     * Compaction status recorded to the timeline (kind=compaction). RUNNING creates the row and backfills seq/kind;
     * the frontend inserts the row locally and then renders the streaming compaction_output body below the divider;
     * COMPLETED/FAILED update it in place; after a refresh the body is parsed from agent_compact_record.summary_after.
     */
    private RuntimeEventDataVO handleCompaction(RuntimeEventDataVO data, CompactionStatus status) {
        if (data.getSessionId() == null) {
            return data;
        }
        String state = switch (status) {
            case PENDING, RUNNING -> TimelineState.RUNNING.getCode();
            case COMPLETED -> TimelineState.COMPLETED.getCode();
            case FAILED -> TimelineState.FAILED.getCode();
        };
        long seq = timelineRecorder.recordCompaction(data.getSessionId(), data.getRunId(),
                data.getPublishId(), data.getCompactRecordId(), state, data.getAssistantReply());
        if (seq > 0) {
            // The frontend inserts the row locally from statusTitle, so it must be the complete title (status prefix + turn counts)
            return data.toBuilder()
                    .seq(seq)
                    .kind(TimelineKind.COMPACTION.getCode())
                    .statusTitle(TimelineRecorder.compactionTitle(state, data.getAssistantReply()))
                    .build();
        }
        return data;
    }

    private void handleClarification(RuntimeEventDataVO data, ClarificationStatus status) {
        if (data.getRunId() == null) {
            return;
        }
        String state = switch (status) {
            case PENDING -> TimelineState.PENDING.getCode();
            case RESPONDED -> TimelineState.ANSWERED.getCode();
            case DISMISSED -> TimelineState.CANCELLED.getCode();
        };
        timelineRecorder.recordClarification(data.getSessionId(), data.getRunId(),
                data.getClarificationId(), state, data.getPrompt());
    }

    private void handleToolApproval(RuntimeEventDataVO data, ToolApprovalStatus status) {
        if (data.getRunId() == null || data.getApprovalId() == null) {
            return;
        }
        ToolApprovalVO approval = toolApprovalSpi.getByApprovalId(data.getApprovalId());
        if (approval == null) {
            return;
        }
        String state = switch (status) {
            case TOOL_APPROVAL_PENDING -> TimelineState.PENDING.getCode();
            case TOOL_APPROVAL_RESPONDED -> TimelineState.ANSWERED.getCode();
            case TOOL_APPROVAL_DISMISSED -> TimelineState.CANCELLED.getCode();
        };
        String title = switch (status) {
            case TOOL_APPROVAL_PENDING -> "Waiting for tool approval";
            case TOOL_APPROVAL_RESPONDED -> "Tool approval handled";
            case TOOL_APPROVAL_DISMISSED -> "Tool approval cancelled";
        };
        timelineRecorder.recordToolApproval(data.getSessionId(), data.getRunId(),
                data.getApprovalId(), approval.getId(), state, title);
    }

    private void flushReasoning(RunVO run) {
        String reasoning = reasoningBufferStore.drain(run.getId());
        if (reasoning == null) {
            return;
        }
        int maxChars = runtimeProperties.getRunner().getMaxReasoningChars();
        if (reasoning.length() > maxChars) {
            reasoning = reasoning.substring(0, maxChars);
        }
        // Persistence guard: strip NUL, otherwise the PG text column rejects it and the run finalization update throws
        run.setReasoning(TextSanitizer.sanitize(reasoning));
    }

    /** Whether the tool name is the todo tool (its args must be kept intact for the frontend checklist rendering). */
    private boolean isTodoArgs(String toolName) {
        return toolName != null && toolName.equals(
                InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId());
    }

    private void handleToolCallLifecycle(RuntimeEventDataVO data, ToolCallStatus status) {
        if (data.getRunId() == null) return;
        // Use publishId hash as stepId so each tool call maps to its own record.
        // Previously all tool calls in the same turn used runId as stepId, causing
        // concurrent tool results to overwrite each other's records.
        // 0x7FFFFFFF masks off the sign bit so the result is always non-negative,
        // fitting cleanly into a DB bigint column.
        long stepId = data.getPublishId() != null
                ? data.getPublishId().hashCode() & 0x7FFFFFFF
                : data.getRunId();
        switch (status) {
            case PENDING -> {
                String prefix = RuntimeEventTypeConstant.PUBLISH_ID_TOOL;
                String callId = data.getPublishId() != null && data.getPublishId().startsWith(prefix)
                        ? data.getPublishId().substring(prefix.length()) : null;
                String rawArgs = data.getArgumentsJson() != null ? data.getArgumentsJson() : "";
                String compressedArgs = isTodoArgs(data.getToolName())
                        ? ToolResultCompressor.compressKeepArrays(rawArgs, RunnerConstants.TOOL_RESULT_MAX_CHARS)
                        : ToolResultCompressor.compress(rawArgs, RunnerConstants.TOOL_RESULT_MAX_CHARS);
                AgentToolCallRecordVO vo = toolCallRecordSpi.createRecord(
                        stepId, callId, data.getRunId(), data.getSessionId(),
                        data.getToolName() != null ? data.getToolName() : "",
                        data.getDisplayNameCn(), data.getDisplayNameEn(), rawArgs, data.getSubAgentRunId(),
                        data.getSubAgentRunId() == null ? data.getTurnNo() : null);
                if (compressedArgs != null) {
                    toolCallRecordSpi.updateCompressedArguments(vo.getId(), compressedArgs);
                }
            }
            case RUNNING -> {
                AgentToolCallRecordVO vo = toolCallRecordSpi.getLatestByStepId(stepId);
                if (vo != null) {
                    toolCallRecordSpi.updateStatus(vo.getId(), status.name(), null, null);
                    if (data.getSubAgentRunId() == null) {
                        timelineRecorder.recordTool(data.getSessionId(), data.getRunId(), String.valueOf(stepId),
                                vo.getId(), TimelineState.IN_PROGRESS.getCode(),
                                data.getDisplayNameCn() != null ? data.getDisplayNameCn() : data.getToolName());
                    }
                }
            }
            case SUCCEEDED -> {
                AgentToolCallRecordVO vo = toolCallRecordSpi.getLatestByStepId(stepId);
                if (vo == null) return;
                String raw = data.getArtifact();
                String compressed = null;
                if (Boolean.TRUE.equals(data.getCompressEnabled())) {
                    compressed = LiveToolResultPolicy.isSkillLibraryTool(data.getToolName())
                            ? ToolResultCompressor.compressKeepArrays(raw, RunnerConstants.TOOL_RESULT_MAX_CHARS)
                            : ToolResultCompressor.compress(raw, RunnerConstants.TOOL_RESULT_MAX_CHARS);
                }
                toolCallRecordSpi.updateStatus(vo.getId(), status.name(), raw, null);
                if (compressed != null) {
                    toolCallRecordSpi.updateCompressedArtifact(vo.getId(), compressed);
                }
                if (data.getSubAgentRunId() == null) {
                    timelineRecorder.recordTool(data.getSessionId(), data.getRunId(), String.valueOf(stepId),
                            vo.getId(), TimelineState.SUCCEEDED.getCode(),
                            data.getDisplayNameCn() != null ? data.getDisplayNameCn() : data.getToolName());
                    timelineRecorder.removeTool(data.getRunId(), String.valueOf(stepId));
                }
            }
            case FAILED -> {
                AgentToolCallRecordVO vo = toolCallRecordSpi.getLatestByStepId(stepId);
                if (vo == null) return;
                String errorMsg = data.getErrorMessage() != null ? data.getErrorMessage() : "Unknown error";
                String errorJson = "{\"error\":\"" + errorMsg + "\"}";
                String compressed = ToolResultCompressor.compress(errorJson, RunnerConstants.TOOL_RESULT_MAX_CHARS);
                toolCallRecordSpi.updateStatus(vo.getId(), status.name(), data.getArtifact(), errorMsg);
                if (compressed != null) {
                    toolCallRecordSpi.updateCompressedArtifact(vo.getId(), compressed);
                }
                if (data.getSubAgentRunId() == null) {
                    timelineRecorder.recordTool(data.getSessionId(), data.getRunId(), String.valueOf(stepId),
                            vo.getId(), TimelineState.FAILED.getCode(),
                            data.getDisplayNameCn() != null ? data.getDisplayNameCn() : data.getToolName());
                    timelineRecorder.removeTool(data.getRunId(), String.valueOf(stepId));
                }
            }
        }
    }

    private void handleUserInLoopLifecycle(RuntimeEventDataVO data, UserInLoopRecordStatus status) {
        if (data.getRunId() == null) return;
        switch (status) {
            case WAITING -> {
                if (data.getSessionId() == null) return;
                hitlRecordSpi.createRecord(
                        data.getRunId(), data.getRunId(), data.getSessionId(),
                        data.getType() != null ? data.getType() : "APPROVAL",
                        data.getPrompt());
            }
            case RESPONDED -> {
                AgentUserInLoopRecordVO vo = hitlRecordSpi.getByStepId(data.getRunId());
                if (vo == null) return;
                String response = data.getResponse() != null ? data.getResponse() : "";
                String result = data.getApprovedBy() != null ? "APPROVED" : "REJECTED";
                hitlRecordSpi.respondRecord(vo.getId(), response, data.getApprovedBy(), result, data.getComment());
            }
        }
    }

    private void handleSessionLifecycle(RuntimeEventDataVO data, SessionStatus status) {
    }
}
