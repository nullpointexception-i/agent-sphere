package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiEventType;
import com.buukle.agent.agui.dtvo.AguiEventVO;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.EventType;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.SessionStatus;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final RedisEventBus eventBus;
    private final Map<String, RunStreamState> states = new ConcurrentHashMap<>();

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
        Long runId = data.getRunId();
        String stateKey = stateKey(sessionId, runId);
        RunStreamState state = states.computeIfAbsent(stateKey, k -> new RunStreamState());
        synchronized (state) {
            List<AguiEventVO> aguiEvents = translate(event);
            if (aguiEvents == null) {
                return;
            }
            // 翻译（含累积状态）留在执行副本，产物经事件总线广播，各副本 relay 投本地 emitter
            for (AguiEventVO agui : aguiEvents) {
                eventBus.publish(DistributedRuntimeConstants.TOPIC_AGUI,
                        new AguiEventEnvelope(sessionId, runId, false, agui));
            }
            if (isTerminal(event.getEventType())) {
                eventBus.publish(DistributedRuntimeConstants.TOPIC_AGUI,
                        new AguiEventEnvelope(sessionId, runId, true, null));
                states.remove(stateKey);
            }
        }
    }

    List<AguiEventVO> translate(RuntimeEventVO event) {
        EventType type = event.getEventType();
        RuntimeEventDataVO data = event.getData();
        Long sessionId = data.getSessionId();
        String threadId = String.valueOf(sessionId);
        String runId = data.getRunId() == null ? String.valueOf(sessionId) : String.valueOf(data.getRunId());

        RunStreamState state = states.computeIfAbsent(stateKey(sessionId, data.getRunId()), k -> new RunStreamState());
        List<AguiEventVO> out = new ArrayList<>();
        if (type instanceof RunStatus runStatus) {
            switch (runStatus) {
                case PENDING -> out.add(ev(AguiEventType.RUN_STARTED, runStarted(threadId, runId)));
                case RUNNING -> {
                }
                case COMPLETED -> {
                    closeOpenMessages(out, state);
                    out.add(ev(AguiEventType.RUN_FINISHED, runFinished(threadId, runId)));
                }
                case FAILED -> {
                    closeOpenMessages(out, state);
                    out.add(ev(AguiEventType.RUN_ERROR,
                            runError(threadId, runId, data.getErrorMessage())));
                }
                case CANCELLED -> {
                    closeOpenMessages(out, state);
                    out.add(ev(AguiEventType.RUN_ERROR, runError(threadId, runId, AguiConstants.ERROR_MESSAGE_RUN_CANCELLED)));
                }
                case AWAITING_USER -> {
                    closeOpenMessages(out, state);
                    out.add(ev(AguiEventType.RUN_FINISHED, runFinishedInterrupt(threadId, runId, state)));
                }
                default -> {
                }
            }
            return out;
        }
        if (type instanceof FlowEventType flow) {
            switch (flow) {
                case CONTENT_TOKEN -> {
                    if (state.textOpen && !Objects.equals(state.textRunId, runId)) {
                        closeOpenText(out, state);
                    }
                    if (!state.textOpen) {
                        state.textOpen = true;
                        state.textRunId = runId;
                        out.add(ev(AguiEventType.TEXT_MESSAGE_START, textStart(runId)));
                    }
                    out.add(ev(AguiEventType.TEXT_MESSAGE_CONTENT, textContent(runId, data.getResponse())));
                }
                case REASONING_TOKEN -> {
                    if (state.reasoningOpen && !Objects.equals(state.reasoningRunId, runId)) {
                        closeOpenReasoning(out, state);
                    }
                    if (!state.reasoningOpen) {
                        state.reasoningOpen = true;
                        state.reasoningRunId = runId;
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
            // 底层 publishId（模型侧工具调用 id）可能在同会话内被复用（ReAct 重调/resume/模型复用），
            // 直接作为 AG-UI toolCallId 会导致前端出现重复 id → React duplicate-key 警告。
            // 打开时统一替换为 <base>-<runId>-<seq> 唯一 id，START/ARGS/RESULT/END 全程用唯一 id 关联。
            String toolCallBaseId = data.getPublishId() != null
                    ? data.getPublishId() : AguiConstants.TOOL_CALL_ID_FALLBACK_PREFIX + runId;
            switch (tool) {
                case PENDING -> {
                    closeOpenToolCall(out, state);
                    openToolCall(out, state, toolCallBaseId, runId, displayName(data));
                }
                case RUNNING -> {
                    if (!isOpenToolCall(state, toolCallBaseId, runId)) {
                        openToolCall(out, state, toolCallBaseId, runId, displayName(data));
                    }
                    out.add(ev(AguiEventType.TOOL_CALL_ARGS, toolArgs(state.toolCallId, data.getArgumentsJson())));
                }
                case SUCCEEDED -> {
                    if (!isOpenToolCall(state, toolCallBaseId, runId)) {
                        openToolCall(out, state, toolCallBaseId, runId, displayName(data));
                    }
                    out.add(ev(AguiEventType.TOOL_CALL_RESULT, toolResult(state.toolCallId, data.getArtifact())));
                    out.add(ev(AguiEventType.TOOL_CALL_END, toolEnd(state.toolCallId)));
                    closeToolCallState(state);
                    maybeEmitTodosSnapshot(out, data.getArtifact());
                }
                case FAILED -> {
                    if (!isOpenToolCall(state, toolCallBaseId, runId)) {
                        openToolCall(out, state, toolCallBaseId, runId, displayName(data));
                    }
                    out.add(ev(AguiEventType.TOOL_CALL_RESULT, toolResult(state.toolCallId, data.getErrorMessage())));
                    out.add(ev(AguiEventType.TOOL_CALL_END, toolEnd(state.toolCallId)));
                    closeToolCallState(state);
                }
                default -> {
                }
            }
            return out;
        }
        if (type instanceof SessionStatus sessionStatus) {
            switch (sessionStatus) {
                case TITLE_UPDATED -> out.add(ev(AguiEventType.CUSTOM,
                        sessionTitleUpdated(sessionId, data.getAssistantReply())));
                default -> {
                }
            }
            return out;
        }
        if (type instanceof ClarificationStatus clarificationStatus) {
            switch (clarificationStatus) {
                case PENDING -> state.pendingClarification = pendingClarification(
                        data.getClarificationId(), data.getPrompt(), data.getType(), data.getArgumentsJson());
                default -> {
                }
            }
            return out;
        }
        return null;
    }

    /**
     * 澄清（ask_clarification）工具暂停 run 时缓存其信息，供 AWAITING_USER 生成 interrupt 载荷。
     */
    private static PendingClarification pendingClarification(String clarificationId, String prompt,
                                                             String type, String optionsJson) {
        PendingClarification pc = new PendingClarification();
        pc.id = clarificationId == null || clarificationId.isBlank()
                ? AguiConstants.INTERRUPT_ID_FALLBACK : clarificationId;
        pc.reason = prompt == null ? "" : prompt;
        pc.type = type == null ? AguiConstants.CLARIFICATION_TYPE_CONFIRM : type;
        pc.options = parseOptions(optionsJson);
        return pc;
    }

    /**
     * 解析澄清 options：支持字符串数组或对象数组 {@code [{label,value,description}]}，
     * 统一规整为 {@code {label, value}}，避免对象选项在前端显示为空。
     */
    private static List<Map<String, String>> parseOptions(String optionsJson) {
        List<Map<String, String>> options = new ArrayList<>();
        if (optionsJson == null || optionsJson.isBlank()) {
            return options;
        }
        try {
            JsonNode node = JsonUtils.parse(optionsJson, JsonNode.class);
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    Map<String, String> option = new LinkedHashMap<>();
                    if (item.isObject()) {
                        String label = item.path(AguiConstants.FIELD_LABEL).asText("");
                        String value = item.path(AguiConstants.FIELD_VALUE).asText("");
                        if (label.isBlank() && value.isBlank()) {
                            continue;
                        }
                        option.put(AguiConstants.FIELD_LABEL,
                                label.isBlank() ? value : label);
                        option.put(AguiConstants.FIELD_VALUE,
                                value.isBlank() ? label : value);
                    } else {
                        String text = item.asText("");
                        if (text.isBlank()) {
                            continue;
                        }
                        option.put(AguiConstants.FIELD_LABEL, text);
                        option.put(AguiConstants.FIELD_VALUE, text);
                    }
                    options.add(option);
                }
            }
        } catch (Exception e) {
            // 非数组 options，忽略
        }
        return options;
    }

    /**
     * 澄清中断：RUN_FINISHED 的 outcome 为 interrupt（AG-UI 协议），携带澄清 id/reason/metadata。
     */
    private static Map<String, Object> runFinishedInterrupt(String threadId, String runId, RunStreamState state) {
        Map<String, Object> map = payload(AguiEventType.RUN_FINISHED.getValue());
        map.put(AguiConstants.FIELD_THREAD_ID, threadId);
        map.put(AguiConstants.FIELD_RUN_ID, runId);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put(AguiConstants.FIELD_TYPE, AguiConstants.OUTCOME_TYPE_INTERRUPT);
        PendingClarification pc = state.pendingClarification != null
                ? state.pendingClarification : new PendingClarification();
        List<Map<String, Object>> interrupts = new ArrayList<>();
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put(AguiConstants.FIELD_ID, pc.id != null ? pc.id : AguiConstants.INTERRUPT_ID_FALLBACK);
        interrupt.put(AguiConstants.FIELD_REASON, pc.reason);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AguiConstants.FIELD_TYPE, pc.type);
        metadata.put(AguiConstants.FIELD_OPTIONS, pc.options);
        metadata.put(AguiConstants.FIELD_SESSION_ID, threadId);
        metadata.put(AguiConstants.FIELD_RUN_ID, runId);
        interrupt.put(AguiConstants.FIELD_METADATA, metadata);
        interrupts.add(interrupt);
        outcome.put(AguiConstants.FIELD_INTERRUPTS, interrupts);
        map.put(AguiConstants.FIELD_OUTCOME, outcome);
        return map;
    }

    /**
     * 会话标题更新（自动生成/手动重命名）以 AG-UI CUSTOM 事件推给前端，
     * widget 据此实时刷新会话列表标题。{@code value} 携带 sessionId 与 title。
     */
    private static Map<String, Object> sessionTitleUpdated(Long sessionId, String title) {
        Map<String, Object> map = payload(AguiEventType.CUSTOM.getValue());
        map.put(AguiConstants.FIELD_NAME, AguiConstants.CUSTOM_EVENT_SESSION_TITLE_UPDATED);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(AguiConstants.FIELD_SESSION_ID, sessionId);
        value.put(AguiConstants.FIELD_TITLE, title == null ? "" : title);
        map.put(AguiConstants.FIELD_VALUE, value);
        return map;
    }

    private static boolean isOpenToolCall(RunStreamState state, String toolCallBaseId, String runId) {
        return state.toolCallBaseId != null
                && state.toolCallBaseId.equals(toolCallBaseId)
                && Objects.equals(state.toolRunId, runId);
    }

    /**
     * 打开一次工具调用：为当前 (publishId, runId, seq) 分配 AG-UI 唯一 toolCallId。
     * 同 run 内重复出现同一 base id 时 seq 递增，跨 run 时 runId 不同 → 全局唯一。
     */
    private static void openToolCall(List<AguiEventVO> out, RunStreamState state,
                                     String toolCallBaseId, String runId, String toolName) {
        if (state.lastToolCallBaseId != null && state.lastToolCallBaseId.equals(toolCallBaseId)) {
            log.warn("AG-UI translator: toolCallId '{}' repeated in run {}, assigning unique id", toolCallBaseId, runId);
        }
        closeOpenToolCall(out, state);
        state.toolCallBaseId = toolCallBaseId;
        state.toolCallId = uniqueToolCallId(toolCallBaseId, runId, ++state.toolCallSeq);
        state.toolRunId = runId;
        out.add(ev(AguiEventType.TOOL_CALL_START, toolStart(state.toolCallId, toolName)));
    }

    private static String uniqueToolCallId(String toolCallBaseId, String runId, int seq) {
        return toolCallBaseId + "-" + runId + "-" + seq;
    }

    /**
     * 工具展示名：优先中文业务名（displayNameCn），其次英文业务名（displayNameEn），
     * 兜底内部 LLM 工具名（如 builtin_3）。避免前端展示 builtin_ 之类的内部名。
     */
    private static String displayName(RuntimeEventDataVO data) {
        if (data.getDisplayNameCn() != null && !data.getDisplayNameCn().isBlank()) {
            return data.getDisplayNameCn();
        }
        if (data.getDisplayNameEn() != null && !data.getDisplayNameEn().isBlank()) {
            return data.getDisplayNameEn();
        }
        return data.getToolName();
    }

    private static boolean isTerminal(EventType type) {
        return type instanceof RunStatus runStatus
                && (runStatus == RunStatus.COMPLETED
                || runStatus == RunStatus.FAILED
                || runStatus == RunStatus.CANCELLED
                || runStatus == RunStatus.AWAITING_USER);
    }

    private static void closeOpenMessages(List<AguiEventVO> out, RunStreamState state) {
        closeOpenReasoning(out, state);
        closeOpenText(out, state);
        closeOpenToolCall(out, state);
    }

    private static void closeOpenText(List<AguiEventVO> out, RunStreamState state) {
        if (state.textOpen) {
            out.add(ev(AguiEventType.TEXT_MESSAGE_END, textEnd(state.textRunId)));
            state.textOpen = false;
            state.textRunId = null;
        }
    }

    private static void closeOpenReasoning(List<AguiEventVO> out, RunStreamState state) {
        if (state.reasoningOpen) {
            out.add(ev(AguiEventType.REASONING_MESSAGE_END, reasoningEnd(state.reasoningRunId)));
            state.reasoningOpen = false;
            state.reasoningRunId = null;
        }
    }

    private static void closeOpenToolCall(List<AguiEventVO> out, RunStreamState state) {
        if (state.toolCallId != null) {
            out.add(ev(AguiEventType.TOOL_CALL_END, toolEnd(state.toolCallId)));
            closeToolCallState(state);
        }
    }

    private static void closeToolCallState(RunStreamState state) {
        state.lastToolCallBaseId = state.toolCallBaseId;
        state.toolCallBaseId = null;
        state.toolCallId = null;
        state.toolRunId = null;
    }

    /**
     * todowrite 工具成功时，把产物 {@code {"todos":[{content,status,priority}]}} 以
     * AG-UI STATE_SNAPSHOT 事件推给前端（widget 据此渲染任务清单块）。非 todos 产物忽略。
     */
    private static void maybeEmitTodosSnapshot(List<AguiEventVO> out, String artifact) {
        if (artifact == null || artifact.isBlank()) {
            return;
        }
        try {
            JsonNode node = JsonUtils.parse(artifact, JsonNode.class);
            JsonNode todos = node != null ? node.get(AguiConstants.FIELD_TODOS) : null;
            if (todos == null || !todos.isArray()) {
                return;
            }
            List<Object> list = new ArrayList<>();
            for (JsonNode item : todos) {
                Map<String, Object> todo = new LinkedHashMap<>();
                todo.put(AguiConstants.FIELD_CONTENT, item.path(AguiConstants.FIELD_CONTENT).asText());
                todo.put(AguiConstants.FIELD_STATUS, item.path(AguiConstants.FIELD_STATUS).asText());
                todo.put(AguiConstants.FIELD_PRIORITY, item.path(AguiConstants.FIELD_PRIORITY).asText());
                list.add(todo);
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put(AguiConstants.FIELD_TODOS, list);
            Map<String, Object> payload = payload(AguiEventType.STATE_SNAPSHOT.getValue());
            payload.put(AguiConstants.FIELD_SNAPSHOT, snapshot);
            out.add(ev(AguiEventType.STATE_SNAPSHOT, payload));
        } catch (Exception e) {
            // 非 todos 产物，静默忽略
        }
    }

    private static AguiEventVO ev(AguiEventType type, Map<String, Object> payload) {
        return new AguiEventVO(type.getValue(), JsonUtils.toJson(payload));
    }

    private static Map<String, Object> payload(String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(AguiConstants.FIELD_TYPE, type);
        return map;
    }

    private static Map<String, Object> runStarted(String threadId, String runId) {
        Map<String, Object> map = payload(AguiEventType.RUN_STARTED.getValue());
        map.put(AguiConstants.FIELD_THREAD_ID, threadId);
        map.put(AguiConstants.FIELD_RUN_ID, runId);
        return map;
    }

    private static Map<String, Object> runFinished(String threadId, String runId) {
        Map<String, Object> map = payload(AguiEventType.RUN_FINISHED.getValue());
        map.put(AguiConstants.FIELD_THREAD_ID, threadId);
        map.put(AguiConstants.FIELD_RUN_ID, runId);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put(AguiConstants.FIELD_TYPE, AguiConstants.OUTCOME_TYPE_SUCCESS);
        map.put(AguiConstants.FIELD_OUTCOME, outcome);
        return map;
    }

    private static Map<String, Object> runError(String threadId, String runId, String message) {
        Map<String, Object> map = payload(AguiEventType.RUN_ERROR.getValue());
        map.put(AguiConstants.FIELD_THREAD_ID, threadId);
        map.put(AguiConstants.FIELD_RUN_ID, runId);
        map.put(AguiConstants.FIELD_MESSAGE, message == null ? AguiConstants.ERROR_MESSAGE_RUN_FAILED : message);
        return map;
    }

    private static Map<String, Object> textStart(String runId) {
        Map<String, Object> map = payload(AguiEventType.TEXT_MESSAGE_START.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.TEXT_MESSAGE_ID_PREFIX + runId);
        map.put(AguiConstants.FIELD_ROLE, AguiConstants.ROLE_ASSISTANT);
        return map;
    }

    private static Map<String, Object> textContent(String runId, String delta) {
        Map<String, Object> map = payload(AguiEventType.TEXT_MESSAGE_CONTENT.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.TEXT_MESSAGE_ID_PREFIX + runId);
        map.put(AguiConstants.FIELD_DELTA, delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> textEnd(String runId) {
        Map<String, Object> map = payload(AguiEventType.TEXT_MESSAGE_END.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.TEXT_MESSAGE_ID_PREFIX + runId);
        return map;
    }

    private static Map<String, Object> reasoningStart(String runId) {
        Map<String, Object> map = payload(AguiEventType.REASONING_MESSAGE_START.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.REASONING_MESSAGE_ID_PREFIX + runId);
        map.put(AguiConstants.FIELD_ROLE, AguiConstants.ROLE_REASONING);
        return map;
    }

    private static Map<String, Object> reasoningContent(String runId, String delta) {
        Map<String, Object> map = payload(AguiEventType.REASONING_MESSAGE_CONTENT.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.REASONING_MESSAGE_ID_PREFIX + runId);
        map.put(AguiConstants.FIELD_DELTA, delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> reasoningEnd(String runId) {
        Map<String, Object> map = payload(AguiEventType.REASONING_MESSAGE_END.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.REASONING_MESSAGE_ID_PREFIX + runId);
        return map;
    }

    private static Map<String, Object> toolStart(String toolCallId, String toolName) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_START.getValue());
        map.put(AguiConstants.FIELD_TOOL_CALL_ID, toolCallId);
        map.put(AguiConstants.FIELD_TOOL_CALL_NAME, toolName == null ? AguiConstants.DEFAULT_TOOL_NAME : toolName);
        return map;
    }

    private static Map<String, Object> toolArgs(String toolCallId, String delta) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_ARGS.getValue());
        map.put(AguiConstants.FIELD_TOOL_CALL_ID, toolCallId);
        map.put(AguiConstants.FIELD_DELTA, delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> toolEnd(String toolCallId) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_END.getValue());
        map.put(AguiConstants.FIELD_TOOL_CALL_ID, toolCallId);
        return map;
    }

    private static Map<String, Object> toolResult(String toolCallId, String content) {
        Map<String, Object> map = payload(AguiEventType.TOOL_CALL_RESULT.getValue());
        map.put(AguiConstants.FIELD_MESSAGE_ID, AguiConstants.TOOL_RESULT_MESSAGE_ID_PREFIX + toolCallId);
        map.put(AguiConstants.FIELD_TOOL_CALL_ID, toolCallId);
        map.put(AguiConstants.FIELD_CONTENT, content == null ? "" : content);
        return map;
    }

    private static String stateKey(Long sessionId, Long runId) {
        return runId == null ? String.valueOf(sessionId) : sessionId + ":" + runId;
    }

    private static final class RunStreamState {
        private boolean textOpen;
        private String textRunId;
        private boolean reasoningOpen;
        private String reasoningRunId;
        private String toolCallBaseId;
        private String toolCallId;
        private String toolRunId;
        private int toolCallSeq;
        private String lastToolCallBaseId;
        private PendingClarification pendingClarification;
    }

    private static final class PendingClarification {
        private String id;
        private String reason;
        private String type;
        private List<Map<String, String>> options = new ArrayList<>();
    }
}
