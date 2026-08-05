package com.buukle.agent.agui.service;

import com.buukle.agent.agui.dtvo.AguiEventVO;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
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
    void runIdChange_shouldCloseOpenTextMessageBeforeNewStart() throws Exception {
        translator.translate(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                data(1L, 10L).setResponse("old")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                FlowEventType.CONTENT_TOKEN, data(1L, 11L).setResponse("new")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "TEXT_MESSAGE_END", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT");
        assertThat(read(events.get(0)).get("messageId").asText()).isEqualTo("msg-10");
        assertThat(read(events.get(1)).get("messageId").asText()).isEqualTo("msg-11");
    }

    @Test
    void runIdChange_shouldCloseOpenReasoningMessage() throws Exception {
        translator.translate(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                data(1L, 10L).setResponse("think")));

        List<AguiEventVO> events = translator.translate(new RuntimeEventVO(
                FlowEventType.REASONING_TOKEN, data(1L, 11L).setResponse("more")));

        assertThat(events).extracting(AguiEventVO::getName).containsExactly(
                "REASONING_MESSAGE_END", "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT");
        assertThat(read(events.get(0)).get("messageId").asText()).isEqualTo("reasoning-10");
        assertThat(read(events.get(1)).get("messageId").asText()).isEqualTo("reasoning-11");
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

    private JsonNode read(AguiEventVO event) throws Exception {
        return objectMapper.readTree(event.getData());
    }
}
