package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiEventType;
import com.buukle.agent.agui.dtvo.AguiEventVO;
import com.buukle.agent.runtime.kernel.port.vo.EventType;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将内部运行时事件（{@link RunStatus}/{@link FlowEventType}/{@link ToolCallStatus}）
 * 翻译为 AG-UI 协议事件流（见 @ag-ui/core 的 EventType）。
 * 每个 SSE data 为含 {@code type} 字段的 JSON，逐条推送给对应 session 的 emitter。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AguiEventTranslator {

    private final AguiStreamManager streamManager;
    private final Map<Long, RunStreamState> states = new ConcurrentHashMap<>();

    @EventListener
    public void onRuntimeEvent(RuntimeEventVO event) {
        if (event == null || event.getData() == null) {
            return;
        }
        RuntimeEventDataVO data = event.getData();
        Long sessionId = data.getSessionId();
        if (sessionId == null) {
            return;
        }
        List<AguiEventVO> aguiEvents = translate(event);
        if (aguiEvents == null) {
            return;
        }
        for (AguiEventVO agui : aguiEvents) {
            streamManager.send(sessionId, agui);
        }
        if (isTerminal(event.getEventType())) {
            streamManager.complete(sessionId);
            states.remove(sessionId);
        }
    }

    List<AguiEventVO> translate(RuntimeEventVO event) {
        EventType type = event.getEventType();
        RuntimeEventDataVO data = event.getData();
        Long sessionId = data.getSessionId();
        String threadId = String.valueOf(sessionId);
        String runId = data.getRunId() == null ? String.valueOf(sessionId) : String.valueOf(data.getRunId());

        RunStreamState state = states.computeIfAbsent(sessionId, k -> new RunStreamState());
        if (!runId.equals(state.runId)) {
            state.runId = runId;
            state.textOpen = false;
            state.reasoningOpen = false;
        }

        List<AguiEventVO> out = new ArrayList<>();
        if (type instanceof RunStatus runStatus) {
            switch (runStatus) {
                case PENDING -> out.add(ev(AguiEventType.RUN_STARTED, runStarted(threadId, runId)));
                case RUNNING -> {
                }
                case COMPLETED -> {
                    closeOpenMessages(out, state, runId);
                    out.add(ev(AguiEventType.RUN_FINISHED, runFinished(threadId, runId)));
                }
                case FAILED -> {
                    closeOpenMessages(out, state, runId);
                    out.add(ev(AguiEventType.RUN_ERROR,
                            runError(threadId, runId, data.getErrorMessage())));
                }
                case CANCELLED -> {
                    closeOpenMessages(out, state, runId);
                    out.add(ev(AguiEventType.RUN_ERROR, runError(threadId, runId, "Run cancelled")));
                }
                case AWAITING_USER -> {
                    closeOpenMessages(out, state, runId);
                    out.add(ev(AguiEventType.RUN_FINISHED, runFinished(threadId, runId)));
                }
                default -> {
                }
            }
            return out;
        }
        if (type instanceof FlowEventType flow) {
            switch (flow) {
                case CONTENT_TOKEN -> {
                    if (!state.textOpen) {
                        state.textOpen = true;
                        out.add(ev(AguiEventType.TEXT_MESSAGE_START, textStart(runId)));
                    }
                    out.add(ev(AguiEventType.TEXT_MESSAGE_CONTENT, textContent(runId, data.getResponse())));
                }
                case REASONING_TOKEN -> {
                    if (!state.reasoningOpen) {
                        state.reasoningOpen = true;
                        out.add(ev(AguiEventType.REASONING_MESSAGE_START, reasoningStart(runId)));
                    }
                    out.add(ev(AguiEventType.REASONING_MESSAGE_CONTENT, reasoningContent(runId, data.getResponse())));
                }
                default -> {
                }
            }
            return out;
        }
        if (type instanceof ToolCallStatus tool) {
            String toolCallId = data.getPublishId() != null ? data.getPublishId() : "tool-" + runId;
            switch (tool) {
                case PENDING -> out.add(ev(AguiEventType.TOOL_CALL_START, toolStart(toolCallId, data.getToolName())));
                case RUNNING -> out.add(ev(AguiEventType.TOOL_CALL_ARGS, toolArgs(toolCallId, data.getArgumentsJson())));
                case SUCCEEDED -> {
                    out.add(ev(AguiEventType.TOOL_CALL_RESULT, toolResult(toolCallId, data.getArtifact())));
                    out.add(ev(AguiEventType.TOOL_CALL_END, toolEnd(toolCallId)));
                }
                case FAILED -> {
                    out.add(ev(AguiEventType.TOOL_CALL_RESULT, toolResult(toolCallId, data.getErrorMessage())));
                    out.add(ev(AguiEventType.TOOL_CALL_END, toolEnd(toolCallId)));
                }
                default -> {
                }
            }
            return out;
        }
        return null;
    }

    private static boolean isTerminal(EventType type) {
        return type instanceof RunStatus runStatus
                && (runStatus == RunStatus.COMPLETED
                || runStatus == RunStatus.FAILED
                || runStatus == RunStatus.CANCELLED
                || runStatus == RunStatus.AWAITING_USER);
    }

    private static void closeOpenMessages(List<AguiEventVO> out, RunStreamState state, String runId) {
        if (state.reasoningOpen) {
            state.reasoningOpen = false;
            out.add(ev(AguiEventType.REASONING_MESSAGE_END, reasoningEnd(runId)));
        }
        if (state.textOpen) {
            state.textOpen = false;
            out.add(ev(AguiEventType.TEXT_MESSAGE_END, textEnd(runId)));
        }
    }

    private static AguiEventVO ev(AguiEventType type, Map<String, Object> payload) {
        return new AguiEventVO(type.getValue(), JsonUtils.toJson(payload));
    }

    private static Map<String, Object> payload(String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        return map;
    }

    private static Map<String, Object> runStarted(String threadId, String runId) {
        Map<String, Object> map = payload(AguiEventType.RUN_STARTED.getValue());
        map.put("threadId", threadId);
        map.put("runId", runId);
        return map;
    }

    private static Map<String, Object> runFinished(String threadId, String runId) {
        Map<String, Object> map = payload(AguiEventType.RUN_FINISHED.getValue());
        map.put("threadId", threadId);
        map.put("runId", runId);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type", "success");
        map.put("outcome", outcome);
        return map;
    }

    private static Map<String, Object> runError(String threadId, String runId, String message) {
        Map<String, Object> map = payload(AguiEventType.RUN_ERROR.getValue());
        map.put("threadId", threadId);
        map.put("runId", runId);
        map.put("message", message == null ? "Run failed" : message);
        return map;
    }

    private static Map<String, Object> textStart(String runId) {
        Map<String, Object> map = payload(AguiEventType.TEXT_MESSAGE_START.getValue());
        map.put("messageId", TEXT_MESSAGE_ID_PREFIX + runId);
        map.put("role", "assistant");
        return map;
    }

    private static Map<String, Object> textContent(String runId, String delta) {
        Map<String, Object> map = payload(AguiEventType.TEXT_MESSAGE_CONTENT.getValue());
        map.put("messageId", TEXT_MESSAGE_ID_PREFIX + runId);
        map.put("delta", delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> textEnd(String runId) {
        Map<String, Object> map = payload(AguiEventType.TEXT_MESSAGE_END.getValue());
        map.put("messageId", TEXT_MESSAGE_ID_PREFIX + runId);
        return map;
    }

    private static Map<String, Object> reasoningStart(String runId) {
        Map<String, Object> map = payload(AguiEventType.REASONING_MESSAGE_START.getValue());
        map.put("messageId", REASONING_MESSAGE_ID_PREFIX + runId);
        map.put("role", "assistant");
        return map;
    }

    private static Map<String, Object> reasoningContent(String runId, String delta) {
        Map<String, Object> map = payload(AguiEventType.REASONING_MESSAGE_CONTENT.getValue());
        map.put("messageId", REASONING_MESSAGE_ID_PREFIX + runId);
        map.put("delta", delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> reasoningEnd(String runId) {
        Map<String, Object> map = payload(AguiEventType.REASONING_MESSAGE_END.getValue());
        map.put("messageId", REASONING_MESSAGE_ID_PREFIX + runId);
        return map;
    }

    private static Map<String, Object> toolStart(String toolCallId, String toolName) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_START.getValue());
        map.put("toolCallId", toolCallId);
        map.put("toolCallName", toolName == null ? "unknown" : toolName);
        return map;
    }

    private static Map<String, Object> toolArgs(String toolCallId, String delta) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_ARGS.getValue());
        map.put("toolCallId", toolCallId);
        map.put("delta", delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> toolEnd(String toolCallId) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_END.getValue());
        map.put("toolCallId", toolCallId);
        return map;
    }

    private static Map<String, Object> toolResult(String toolCallId, String content) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_RESULT.getValue());
        map.put("toolCallId", toolCallId);
        map.put("content", content == null ? "" : content);
        return map;
    }

    private static final String TEXT_MESSAGE_ID_PREFIX = "msg-";
    private static final String REASONING_MESSAGE_ID_PREFIX = "reasoning-";

    private static final class RunStreamState {
        private String runId;
        private boolean textOpen;
        private boolean reasoningOpen;
    }
}
