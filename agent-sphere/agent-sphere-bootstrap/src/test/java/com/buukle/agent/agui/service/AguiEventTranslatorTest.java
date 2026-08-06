package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiEventVO;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.SessionStatus;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AguiEventTranslatorTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AguiStreamManager streamManager;

    AguiEventTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new AguiEventTranslator(streamManager);
    }

    private static RuntimeEventDataVO data(Long sessionId, Long runId) {
        return new RuntimeEventDataVO().setSessionId(sessionId).setRunId(runId);
    }

    @Test
    void contentTokens_shouldOpenAndStreamTextMessage() throws Exception {
        RuntimeEventDataVO first = data(1L, 10L).setResponse("Hello");
        RuntimeEventDataVO second = data(1L, 10L).setResponse(" world");

        List<AguiEventVO> events = translator.translate(
                new RuntimeEventVO(FlowEventType.CONTENT_TOKEN, first));
        events.addAll(translator.translate(
                new RuntimeEventVO(FlowEventType.CONTENT_TOKEN, second)));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT");
        JsonNode start = read(events.get(0));
        assertThat(start.get("type").asText()).isEqualTo("TEXT_MESSAGE_START");
        assertThat(start.get("role").asText()).isEqualTo("assistant");
        assertThat(read(events.get(1)).get("delta").asText()).isEqualTo("Hello");
        assertThat(read(events.get(2)).get("delta").asText()).isEqualTo(" world");
    }

    @Test
    void reasoningTokens_shouldEmitReasoningRole() throws Exception {
        RuntimeEventDataVO first = data(1L, 10L).setResponse("think");
        RuntimeEventDataVO second = data(1L, 10L).setResponse("ing");

        List<AguiEventVO> events = translator.translate(
                new RuntimeEventVO(FlowEventType.REASONING_TOKEN, first));
        events.addAll(translator.translate(
                new RuntimeEventVO(FlowEventType.REASONING_TOKEN, second)));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT", "REASONING_MESSAGE_CONTENT");
        JsonNode start = read(events.get(0));
        assertThat(start.get("type").asText()).isEqualTo("REASONING_MESSAGE_START");
        assertThat(start.get("role").asText()).isEqualTo("reasoning");
        assertThat(read(events.get(1)).get("delta").asText()).isEqualTo("think");
        assertThat(read(events.get(2)).get("delta").asText()).isEqualTo("ing");
    }

    @Test
    void completed_shouldEmitEndAndRunFinished() throws Exception {
        translator.translate(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                data(1L, 10L).setResponse("ok")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.COMPLETED, data(1L, 10L).setAssistantReply("ok")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TEXT_MESSAGE_END", "RUN_FINISHED");
        JsonNode finished = read(events.get(1));
        assertThat(finished.get("type").asText()).isEqualTo("RUN_FINISHED");
        assertThat(finished.get("threadId").asText()).isEqualTo("1");
        assertThat(finished.get("runId").asText()).isEqualTo("10");
        assertThat(finished.get("outcome").get("type").asText()).isEqualTo("success");
    }

    @Test
    void failed_shouldEmitRunError() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.FAILED, data(1L, 10L).setErrorMessage("boom")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly("RUN_ERROR");
        assertThat(read(events.get(0)).get("message").asText()).isEqualTo("boom");
    }

    @Test
    void toolCall_shouldUseDisplayNameWhenPresent() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("builtin_3")
                        .setDisplayNameCn("写待办").setDisplayNameEn("TodoWrite").setPublishId("tool_1")));

        JsonNode start = read(events.get(0));
        assertThat(start.get("toolCallName").asText()).isEqualTo("写待办");
    }

    @Test
    void toolCall_shouldEmitAguiEvents() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("search_web").setPublishId("tool_1")));
        events.addAll(translator.translate(new RuntimeEventVO(
                ToolCallStatus.SUCCEEDED,
                data(1L, 10L).setToolName("search_web").setPublishId("tool_1").setArtifact("{}"))));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_START", "TOOL_CALL_RESULT", "TOOL_CALL_END");
        JsonNode start = read(events.get(0));
        assertThat(start.get("toolCallId").asText()).isEqualTo("tool_1");
        assertThat(start.get("toolCallName").asText()).isEqualTo("search_web");
        JsonNode result = read(events.get(1));
        assertThat(result.get("toolCallId").asText()).isEqualTo("tool_1");
        assertThat(result.get("content").asText()).isEqualTo("{}");
        assertThat(result.get("messageId").asText()).isEqualTo("tool-result-tool_1");
    }

    @Test
    void runStarted_shouldEmitAguiRunStarted() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.PENDING, data(1L, 10L)));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly("RUN_STARTED");
        JsonNode started = read(events.get(0));
        assertThat(started.get("type").asText()).isEqualTo("RUN_STARTED");
        assertThat(started.get("threadId").asText()).isEqualTo("1");
        assertThat(started.get("runId").asText()).isEqualTo("10");
    }

    @Test
    void separateRuns_shouldKeepIndependentTextState() throws Exception {
        translator.translate(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                data(1L, 10L).setResponse("old")));

        // 另一 run 的事件不干扰 run10 的已打开文本
        translator.translate(new RuntimeEventVO(
                FlowEventType.CONTENT_TOKEN, data(1L, 11L).setResponse("other")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                FlowEventType.CONTENT_TOKEN, data(1L, 10L).setResponse("more")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TEXT_MESSAGE_CONTENT");
        assertThat(read(events.get(0)).get("messageId").asText()).isEqualTo("msg-10");
        assertThat(read(events.get(0)).get("delta").asText()).isEqualTo("more");
    }

    @Test
    void separateRuns_shouldKeepIndependentReasoningState() throws Exception {
        translator.translate(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                data(1L, 10L).setResponse("think")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                FlowEventType.REASONING_TOKEN, data(1L, 11L).setResponse("more")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT");
        assertThat(read(events.get(0)).get("messageId").asText()).isEqualTo("reasoning-11");
    }

    @Test
    void completedWithOpenToolCall_shouldEmitToolCallEndBeforeRunFinished() throws Exception {
        translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("search_web").setPublishId("tool_1")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.COMPLETED, data(1L, 10L)));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_END", "RUN_FINISHED");
        assertThat(read(events.get(0)).get("toolCallId").asText()).isEqualTo("tool_1");
    }

    @Test
    void toolCallPendingTwice_shouldClosePreviousBeforeNewStart() throws Exception {
        translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("a").setPublishId("tool_1")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("b").setPublishId("tool_2")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_END", "TOOL_CALL_START");
        assertThat(read(events.get(0)).get("toolCallId").asText()).isEqualTo("tool_1");
        assertThat(read(events.get(1)).get("toolCallId").asText()).isEqualTo("tool_2");
    }

    @Test
    void runningForUnknownToolCall_shouldEmitStartBeforeArgs() throws Exception {
        translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("a").setPublishId("tool_1")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.RUNNING, data(1L, 10L).setToolName("b").setPublishId("tool_2")
                        .setArgumentsJson("{\"q\":\"x\"}")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_END", "TOOL_CALL_START", "TOOL_CALL_ARGS");
        assertThat(read(events.get(0)).get("toolCallId").asText()).isEqualTo("tool_1");
        assertThat(read(events.get(1)).get("toolCallId").asText()).isEqualTo("tool_2");
        assertThat(read(events.get(2)).get("toolCallId").asText()).isEqualTo("tool_2");
    }

    @Test
    void runningInSeparateRun_shouldStartToolInFreshState() throws Exception {
        translator.translate(new RuntimeEventVO(
                ToolCallStatus.PENDING, data(1L, 10L).setToolName("a").setPublishId("tool_1")));

        // run 11 是新状态：RUNNING 先补 START，再发 ARGS；run10 的工具不受影响
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.RUNNING, data(1L, 11L).setToolName("a").setPublishId("tool_1")
                        .setArgumentsJson("{\"q\":\"x\"}")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_START", "TOOL_CALL_ARGS");
        assertThat(read(events.get(0)).get("toolCallId").asText()).isEqualTo("tool_1");
    }

    @Test
    void succeededWithoutStart_shouldEmitStartBeforeResultAndEnd() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.SUCCEEDED,
                data(1L, 10L).setToolName("a").setPublishId("tool_1").setArtifact("{}")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_START", "TOOL_CALL_RESULT", "TOOL_CALL_END");
    }

    @Test
    void todowriteSuccess_shouldEmitStateSnapshotWithTodos() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.SUCCEEDED,
                data(1L, 10L).setToolName("builtin_3").setPublishId("tool_1")
                        .setArtifact("{\"todos\":[{\"content\":\"写文档\",\"status\":\"pending\",\"priority\":\"high\"},{\"content\":\"发邮件\",\"status\":\"completed\",\"priority\":\"low\"}]}")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_START", "TOOL_CALL_RESULT", "TOOL_CALL_END", "STATE_SNAPSHOT");
        JsonNode snapshot = read(events.get(3));
        assertThat(snapshot.get("type").asText()).isEqualTo("STATE_SNAPSHOT");
        assertThat(snapshot.get("snapshot").get("todos").size()).isEqualTo(2);
        assertThat(snapshot.get("snapshot").get("todos").get(0).get("content").asText())
                .isEqualTo("写文档");
        assertThat(snapshot.get("snapshot").get("todos").get(1).get("status").asText())
                .isEqualTo("completed");
    }

    @Test
    void nonTodoToolSuccess_shouldNotEmitStateSnapshot() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                ToolCallStatus.SUCCEEDED,
                data(1L, 10L).setToolName("web_fetch").setPublishId("tool_1").setArtifact("page content")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TOOL_CALL_START", "TOOL_CALL_RESULT", "TOOL_CALL_END");
        assertThat(events).extracting(AguiEventVO::getName).doesNotContain("STATE_SNAPSHOT");
    }

    @Test
    void runIdFluctuation_shouldNotEmitDuplicateTextStart() throws Exception {
        translator.translate(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                data(1L, 10L).setResponse("a")));
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                FlowEventType.CONTENT_TOKEN, data(1L, 11L).setResponse("b")));
        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT");
        assertThat(read(events.get(0)).get("messageId").asText()).isEqualTo("msg-11");

        // run 10 的状态独立保留：回到 10 继续追加内容，不重新 START
        events = translator.translate(new RuntimeEventVO(
                FlowEventType.CONTENT_TOKEN, data(1L, 10L).setResponse("c")));
        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TEXT_MESSAGE_CONTENT");
        assertThat(read(events.get(0)).get("messageId").asText()).isEqualTo("msg-10");
    }

    @Test
    void awaitingUserWithPendingClarification_shouldEmitInterruptRunFinished() throws Exception {
        translator.translate(new RuntimeEventVO(
                ClarificationStatus.PENDING,
                new RuntimeEventDataVO().setSessionId(1L).setRunId(10L)
                        .setClarificationId("abc123")
                        .setPrompt("确认执行删除？")
                        .setType("confirm")
                        .setArgumentsJson("[\"删除\",\"取消\"]")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.AWAITING_USER, data(1L, 10L)));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly("RUN_FINISHED");
        JsonNode payload = read(events.get(0));
        assertThat(payload.get("outcome").get("type").asText()).isEqualTo("interrupt");
        JsonNode interrupt = payload.get("outcome").get("interrupts").get(0);
        assertThat(interrupt.get("id").asText()).isEqualTo("abc123");
        assertThat(interrupt.get("reason").asText()).isEqualTo("确认执行删除？");
        assertThat(interrupt.get("metadata").get("type").asText()).isEqualTo("confirm");
        assertThat(interrupt.get("metadata").get("options").get(0).get("label").asText()).isEqualTo("删除");
        assertThat(interrupt.get("metadata").get("options").get(0).get("value").asText()).isEqualTo("删除");
        assertThat(interrupt.get("metadata").get("sessionId").asText()).isEqualTo("1");
        assertThat(interrupt.get("metadata").get("runId").asText()).isEqualTo("10");
    }

    @Test
    void awaitingUserWithObjectOptions_shouldExtractLabelAndValue() throws Exception {
        translator.translate(new RuntimeEventVO(
                ClarificationStatus.PENDING,
                new RuntimeEventDataVO().setSessionId(1L).setRunId(10L)
                        .setClarificationId("abc123")
                        .setPrompt("选择操作")
                        .setType("choice")
                        .setArgumentsJson("[{\"label\":\"删除\",\"value\":\"delete\"},{\"label\":\"保留\",\"value\":\"keep\",\"description\":\"不删除\"}]")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.AWAITING_USER, data(1L, 10L)));

        JsonNode options = read(events.get(0)).get("outcome").get("interrupts").get(0)
                .get("metadata").get("options");
        assertThat(options.size()).isEqualTo(2);
        assertThat(options.get(0).get("label").asText()).isEqualTo("删除");
        assertThat(options.get(0).get("value").asText()).isEqualTo("delete");
        assertThat(options.get(1).get("label").asText()).isEqualTo("保留");
        assertThat(options.get(1).get("value").asText()).isEqualTo("keep");
    }

    @Test
    void awaitingUserWithoutPending_shouldEmitInterruptWithFallbackId() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                RunStatus.AWAITING_USER, data(1L, 10L)));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly("RUN_FINISHED");
        JsonNode interrupt = read(events.get(0)).get("outcome").get("interrupts").get(0);
        assertThat(interrupt.get("id").asText()).isEqualTo("clarification");
    }

    @Test
    void titleUpdated_shouldEmitCustomSessionTitleUpdatedEvent() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                SessionStatus.TITLE_UPDATED,
                new RuntimeEventDataVO().setSessionId(1L).setRunId(10L).setAssistantReply("新标题")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly("CUSTOM");
        JsonNode payload = read(events.get(0));
        assertThat(payload.get("type").asText()).isEqualTo("CUSTOM");
        assertThat(payload.get("name").asText()).isEqualTo("session_title_updated");
        assertThat(payload.get("value").get("sessionId").asLong()).isEqualTo(1L);
        assertThat(payload.get("value").get("title").asText()).isEqualTo("新标题");
    }

    @Test
    void nonTerminalSessionStatus_shouldNotCompleteStream() throws Exception {
        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                SessionStatus.TITLE_UPDATED,
                new RuntimeEventDataVO().setSessionId(1L).setRunId(10L).setAssistantReply("标题")));

        assertThat(events).isNotEmpty();
    }

    private JsonNode read(AguiEventVO event) throws Exception {
        return objectMapper.readTree(event.getData());
    }
}
