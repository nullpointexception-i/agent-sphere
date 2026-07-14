package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentUserInLoopRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.AgentUserInLoopRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.*;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import com.buukle.agent.runtime.orchestration.sse.SseManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeEventListener {
    private final SseManager sseManager;
    private final RunSpi runSpi;
    private final AgentUserInLoopRecordSpi hitlRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;

    private static RuntimeEventVO transformForFrontend(RuntimeEventVO event) {
        EventType type = event.getEventType();
        return switch (type) {
            case ToolCallStatus t -> wrapReasoning(event, toolCallSubType(t));
            case CompactionStatus c -> wrapReasoning(event, compactSubType(c));
            case SessionStatus s -> wrapReasoning(event, sessionSubType(s));
            case RunStatus r -> wrapReasoning(event, runSubType(r));
            default -> event;
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
        };
    }

    private static RuntimeEventVO wrapReasoning(RuntimeEventVO event, String subType) {
        RuntimeEventDataVO src = event.getData();
        RuntimeEventDataVO dst = new RuntimeEventDataVO()
                .setSessionId(src.getSessionId())
                .setRunId(src.getRunId())
                .setPublishId(src.getPublishId())
                .setReasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                .setReasoningSubType(subType)
                .setAssistantReply(src.getAssistantReply())
                .setArtifact(src.getArtifact())
                .setArgumentsJson(src.getArgumentsJson())
                .setErrorMessage(src.getErrorMessage())
                .setType(src.getType());

        String name = src.getDisplayNameCn() != null ? src.getDisplayNameCn()
                : (src.getToolName() != null ? src.getToolName() : "");
        dst.setDisplayNameCn(name);
        dst.setDisplayNameEn(src.getDisplayNameEn());
        dst.setToolName(src.getToolName());

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
            case RuntimeEventTypeConstant.REASONING_SUB_TYPE_RUN_AWAITING_USER -> "⏸️ Awaiting user clarification";
            default -> "";
        };
        dst.setResponse(text);

        return new RuntimeEventVO(FlowEventType.REASONING_TOKEN, dst);
    }

    @EventListener
    public void onEvent(RuntimeEventVO event) {
        EventType eventType = event.getEventType();
        RuntimeEventDataVO data = event.getData();

        switch (eventType) {
            case RunStatus r -> handleRunLifecycle(data, r);
            case ToolCallStatus s -> handleToolCallLifecycle(data, s);
            case UserInLoopRecordStatus s -> handleUserInLoopLifecycle(data, s);
            case CompactionStatus s -> {
            }
            case SessionStatus s -> handleSessionLifecycle(data, s);
            case FlowEventType f -> {
            }
            case ScreenshotEventType s -> {
            }
            case ChromeCommandEventType s -> {
            }
            case ClarificationStatus s -> { /* handled via SSE passthrough */ }
        }

        Long sessionId = data != null ? data.getSessionId() : null;
        if (sessionId != null) {
            sseManager.sendBySession(sessionId, transformForFrontend(event));
        }
    }

    private void handleRunLifecycle(RuntimeEventDataVO data, RunStatus status) {
        if (data.getRunId() == null) return;
        RunVO run = runSpi.getRun(data.getRunId());
        if (run == null) return;
        switch (status) {
            case PENDING, RUNNING -> {
                run.setStatus(status.value());
                if (data.getType() != null) run.setType(data.getType());
                runSpi.updateRun(run);
            }
            case COMPLETED -> {
                run.setStatus(status.value());
                if (data.getType() != null) run.setType(data.getType());
                if (data.getAssistantReply() != null) run.setAssistantReply(data.getAssistantReply());
                runSpi.updateRun(run);
            }
            case FAILED -> {
                run.setStatus(status.value());
                runSpi.updateRun(run);
            }
            case AWAITING_USER -> {
                run.setStatus(status.value());
                runSpi.updateRun(run);
            }
        }
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
                String compressedArgs = ToolResultCompressor.compress(rawArgs, RunnerConstants.TOOL_RESULT_MAX_CHARS);
                AgentToolCallRecordVO vo = toolCallRecordSpi.createRecord(
                        stepId, callId, data.getRunId(), data.getSessionId(),
                        data.getToolName() != null ? data.getToolName() : "",
                        data.getDisplayNameCn(), data.getDisplayNameEn(), rawArgs);
                if (compressedArgs != null) {
                    toolCallRecordSpi.updateCompressedArguments(vo.getId(), compressedArgs);
                }
            }
            case RUNNING -> {
                AgentToolCallRecordVO vo = toolCallRecordSpi.getLatestByStepId(stepId);
                if (vo != null) toolCallRecordSpi.updateStatus(vo.getId(), status.name(), null, null);
            }
            case SUCCEEDED -> {
                AgentToolCallRecordVO vo = toolCallRecordSpi.getLatestByStepId(stepId);
                if (vo == null) return;
                String raw = data.getArtifact();
                String compressed = ToolResultCompressor.compress(raw, RunnerConstants.TOOL_RESULT_MAX_CHARS);
                toolCallRecordSpi.updateStatus(vo.getId(), status.name(), raw, null);
                if (compressed != null) {
                    toolCallRecordSpi.updateCompressedArtifact(vo.getId(), compressed);
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
