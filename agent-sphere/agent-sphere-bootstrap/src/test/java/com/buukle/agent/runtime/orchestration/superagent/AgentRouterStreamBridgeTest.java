package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.channel.spi.AgentStreamEvent;
import com.buukle.agent.channel.spi.AgentStreamPhase;
import com.buukle.agent.channel.spi.AgentStreamPort;
import com.buukle.agent.channel.spi.AgentStreamStatus;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.superagent.spi.AgentRouterReplyPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link AgentRouterStreamBridge} deterministically: events are pushed directly
 * and the flush tick is invoked by hand, so assertions do not depend on the daemon
 * flusher's timing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRouterStreamBridgeTest {

    private static final Long SESSION_ID = 5L;
    private static final Long RUN_ID = 7L;
    private static final Long THREAD_ID = 11L;
    private static final Long AGENT_ID = 3L;

    @Mock
    SessionSpi sessionSpi;
    @Mock
    ObjectProvider<AgentStreamPort> streamPortProvider;
    @Mock
    AgentStreamPort streamPort;
    @Mock
    ObjectProvider<AgentRouterReplyPort> replyPortProvider;
    @Mock
    AgentRouterReplyPort replyPort;
    @Mock
    ObjectProvider<MeterRegistry> registryProvider;

    private AgentRouterStreamBridge bridge;

    @BeforeEach
    void setUp() {
        when(streamPortProvider.getIfAvailable()).thenReturn(streamPort);
        when(replyPortProvider.getIfAvailable()).thenReturn(replyPort);
        bridge = new AgentRouterStreamBridge(sessionSpi, streamPortProvider,
                replyPortProvider, new AgentMetrics(registryProvider));
        ReflectionTestUtils.setField(bridge, "streamEnabled", true);
        ReflectionTestUtils.setField(bridge, "thinkingEnabled", true);
        // Effectively disable the daemon flusher; tests drive flushTick() manually.
        ReflectionTestUtils.setField(bridge, "coalesceMs", 1_000_000L);
        ReflectionTestUtils.setField(bridge, "flushInitialDelayMs", 3_600_000L);
        ReflectionTestUtils.setField(bridge, "maxChunkChars", 256);
        ReflectionTestUtils.setField(bridge, "sessionCacheTtlSeconds", 600L);
        ReflectionTestUtils.setField(bridge, "bufferTtlSeconds", 600L);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    @Test
    void replyFragmentsStreamAndFinalReplyIsDelivered() {
        stubRouterSession();

        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("你好")));
        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("！")));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, "fallback"));

        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertTrue(published.stream().anyMatch(e ->
                AgentStreamPhase.REPLY.equals(e.phase()) && "你好！".equals(e.delta())));
        assertTrue(published.stream().anyMatch(e ->
                AgentStreamStatus.COMPLETED.equals(e.streamStatus())));
        verify(replyPort).sendFinalReply(SESSION_ID, RUN_ID, THREAD_ID, "你好！");
    }

    @Test
    void toolCallResetsFinalReplyBuffer() {
        stubRouterSession();

        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("先看一下")));
        bridge.onEvent(toolEvent(ToolCallStatus.PENDING));
        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("最终答复")));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));

        bridge.flushTick();

        verify(replyPort).sendFinalReply(SESSION_ID, RUN_ID, THREAD_ID, "最终答复");
        List<AgentStreamEvent> published = capturedStreamEvents();
        assertTrue(published.stream().anyMatch(e ->
                AgentStreamStatus.TOOL_PENDING.equals(e.streamStatus())));
    }

    @Test
    void plainSendMessageTextStreamsImmediatelyAndLaterContentIsSuppressed() {
        stubRouterSession();

        RuntimeEventDataVO sent = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID)
                .toolName("builtin_" + BuiltinToolEnum.SEND_MESSAGE.getId())
                .argumentsJson("{\"format\":\"TEXT\",\"text\":\"立即显示的回复\"}")
                .build();
        bridge.onEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED, sent));
        // A later LLM turn restating the answer must not duplicate the bubble.
        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("稍后重复的回复")));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));

        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertTrue(published.stream().anyMatch(e ->
                AgentStreamPhase.REPLY.equals(e.phase()) && "立即显示的回复".equals(e.delta())));
        assertFalse(published.stream().anyMatch(e -> "稍后重复的回复".equals(e.delta())));
    }

    @Test
    void cardSendMessageIsNotStreamedAsReply() {
        stubRouterSession();

        RuntimeEventDataVO card = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID)
                .toolName("builtin_" + BuiltinToolEnum.SEND_MESSAGE.getId())
                .argumentsJson("{\"format\":\"CARD\",\"cardTitle\":\"任务卡片\",\"text\":\"卡片\"}")
                .build();
        bridge.onEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED, card));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));

        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertFalse(published.stream().anyMatch(e -> AgentStreamPhase.REPLY.equals(e.phase())));
    }

    @Test
    void finalReplyFallsBackToAssistantReplyWhenBufferEmpty() {
        stubRouterSession();

        bridge.onEvent(runEvent(RunStatus.COMPLETED, "来自 run 的最终正文"));

        bridge.flushTick();

        verify(replyPort).sendFinalReply(SESSION_ID, RUN_ID, THREAD_ID, "来自 run 的最终正文");
    }

    @Test
    void thinkingFragmentsStreamAndSystemReasoningIsIgnored() {
        stubRouterSession();

        RuntimeEventDataVO llmReasoning = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID).response("思考中")
                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON).build();
        bridge.onEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN, llmReasoning));

        RuntimeEventDataVO systemReasoning = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID).response("系统状态")
                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM).build();
        bridge.onEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN, systemReasoning));

        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));

        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertTrue(published.stream().anyMatch(e ->
                AgentStreamPhase.THINKING.equals(e.phase()) && "思考中".equals(e.delta())));
        assertFalse(published.stream().anyMatch(e -> "系统状态".equals(e.delta())));
    }

    @Test
    void nonRouterSessionIsIgnored() {
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setSessionType(SessionType.CONSOLE);
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(session);

        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("普通聊天")));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, "普通回复"));
        bridge.flushTick();

        verify(streamPort, never()).publish(any());
        verify(replyPort, never()).sendFinalReply(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void subAgentEventsAreIgnored() {
        stubRouterSession();

        RuntimeEventDataVO subAgent = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID).response("子 Agent 输出")
                .subAgentRunId(99L).build();
        bridge.onEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN, subAgent));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));
        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertFalse(published.stream().anyMatch(e -> "子 Agent 输出".equals(e.delta())));
    }

    @Test
    void startedIsPublishedOncePerRun() {
        stubRouterSession();

        bridge.onEvent(runEvent(RunStatus.PENDING, null));
        bridge.onEvent(runEvent(RunStatus.RUNNING, null));
        bridge.onEvent(runEvent(RunStatus.RUNNING, null));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));

        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertEquals(1, published.stream()
                .filter(e -> AgentStreamStatus.STARTED.equals(e.streamStatus()))
                .count());
        // Empty finalReply and no assistantReply: must not trigger an automatic final.
        verify(replyPort, never()).sendFinalReply(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void streamDisabledStillDeliversFinalReply() {
        stubRouterSession();
        ReflectionTestUtils.setField(bridge, "streamEnabled", false);

        bridge.onEvent(event(FlowEventType.CONTENT_TOKEN, data("纯文本回复")));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, null));

        bridge.flushTick();

        verify(streamPort, never()).publish(any());
        verify(replyPort).sendFinalReply(SESSION_ID, RUN_ID, THREAD_ID, "纯文本回复");
    }

    @Test
    void failedRunPublishesErrorStatusWithoutFinalReply() {
        stubRouterSession();

        RuntimeEventDataVO failed = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID)
                .errorMessage("模型超时").build();
        bridge.onEvent(new RuntimeEventVO(RunStatus.FAILED, failed));

        bridge.flushTick();

        List<AgentStreamEvent> published = capturedStreamEvents();
        assertTrue(published.stream().anyMatch(e ->
                AgentStreamStatus.FAILED.equals(e.streamStatus())
                        && "模型超时".equals(e.errorMessage())));
        verify(replyPort, never()).sendFinalReply(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void awaitingUserParksBubbleAndReopensOnResume() {
        stubRouterSession();

        bridge.onEvent(runEvent(RunStatus.RUNNING, null));
        bridge.onEvent(runEvent(RunStatus.AWAITING_USER, null));
        bridge.onEvent(runEvent(RunStatus.AWAITING_USER, null));

        bridge.flushTick();

        List<AgentStreamEvent> parked = capturedStreamEvents();
        assertEquals(1, parked.stream()
                .filter(e -> AgentStreamStatus.AWAITING_USER.equals(e.streamStatus()))
                .count());
        verify(replyPort, never()).sendFinalReply(anyLong(), anyLong(), anyLong(), anyString());

        // User answered the clarification card: the same run resumes and completes.
        bridge.onEvent(runEvent(RunStatus.RUNNING, null));
        bridge.onEvent(runEvent(RunStatus.COMPLETED, "继续回答"));
        bridge.flushTick();

        List<AgentStreamEvent> resumed = capturedStreamEvents();
        assertEquals(2, resumed.stream()
                .filter(e -> AgentStreamStatus.STARTED.equals(e.streamStatus()))
                .count());
        assertTrue(resumed.stream().anyMatch(e ->
                AgentStreamStatus.COMPLETED.equals(e.streamStatus())));
        verify(replyPort).sendFinalReply(SESSION_ID, RUN_ID, THREAD_ID, "继续回答");
    }

    private void stubRouterSession() {
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setSessionType(SessionType.ROUTER);
        session.setSuperAgentId(AGENT_ID);
        session.setThreadId(THREAD_ID);
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(session);
    }

    private static RuntimeEventDataVO data(String text) {
        return RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID).response(text).build();
    }

    private static RuntimeEventVO event(FlowEventType type, RuntimeEventDataVO data) {
        return new RuntimeEventVO(type, data);
    }

    private static RuntimeEventVO runEvent(RunStatus status, String assistantReply) {
        RuntimeEventDataVO data = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID)
                .assistantReply(assistantReply).build();
        return new RuntimeEventVO(status, data);
    }

    private static RuntimeEventVO toolEvent(ToolCallStatus status) {
        RuntimeEventDataVO data = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID)
                .toolName("dispatch_worker").displayNameCn("派发任务").build();
        return new RuntimeEventVO(status, data);
    }

    private List<AgentStreamEvent> capturedStreamEvents() {
        ArgumentCaptor<AgentStreamEvent> events = ArgumentCaptor.forClass(AgentStreamEvent.class);
        org.mockito.Mockito.verify(streamPort, org.mockito.Mockito.atLeast(0)).publish(events.capture());
        return events.getAllValues();
    }
}
