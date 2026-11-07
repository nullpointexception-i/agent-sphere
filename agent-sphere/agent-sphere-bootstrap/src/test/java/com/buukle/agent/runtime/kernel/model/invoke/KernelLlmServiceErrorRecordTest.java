package com.buukle.agent.runtime.kernel.model.invoke;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.complete.LlmStreamStats;
import com.buukle.agent.model.spi.ModelProviderSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;


/**
 * Guard for failure classification of LLM call records.
 *
 * <p>Production once saw a whole batch of runs killed by "instant errors from the model side", while in {@code agent_llm_interaction_record}
 * those records were <b>all success=true / error_message=NULL</b> — because the provider reports errors as an {@link LLMEvent.Error}
 * <b>event</b> (not an exception), a path that never passes through catch. Afterwards the only clue was "<1s duration + zero tokens",
 * reverse-engineered, with the error cause completely lost. This test locks down "event errors must also be recorded as failures".
 */
@ExtendWith(MockitoExtension.class)
class KernelLlmServiceErrorRecordTest {

    @Mock
    ModelProviderSpi modelProviderSpi;
    @Mock
    ApplicationEventPublisher eventPublisher;

    KernelLlmService service;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getLlm().setStreamTimeout(Duration.ofSeconds(5));
        service = new KernelLlmService(modelProviderSpi, eventPublisher, props,
                org.mockito.Mockito.mock(com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi.class));
    }

    /** Make the provider emit an event sequence during streaming (without throwing). */
    private void emitEvents(LLMEvent... events) throws Exception {
        doAnswer(inv -> {
            Consumer<LLMEvent> consumer = inv.getArgument(5);
            Runnable done = inv.getArgument(6);
            for (LLMEvent e : events) {
                consumer.accept(e);
            }
            done.run();
            return LlmStreamStats.empty();
        }).when(modelProviderSpi).streamWithStats(anyString(), anyString(), any(), anyString(),
                any(ChatCompletionRequestDTO.class), any(), any());
    }

    private LlmInteractionEvent captured() throws Exception {
        ArgumentCaptor<LlmInteractionEvent> cap = ArgumentCaptor.forClass(LlmInteractionEvent.class);
        // The interaction record is published in finally, but the future completes <b>before</b> finally,
        // so the event may not have been emitted when get() returns — a timed wait is required.
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.timeout(5000)
                        .atLeastOnce())
                .publishEvent(cap.capture());
        return cap.getValue();
    }

    private static LlmInteractionMeta meta() {
        return new LlmInteractionMeta().setRunId(1L).setSessionId(2L)
                .setInteractionType(LlmInteractionType.CHAT_REPLY);
    }

    @Test
    void eventError_isRecordedAsFailureWithMessage() throws Exception {
        emitEvents(new LLMEvent.Error("rate limited by upstream"));

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), e -> {
                }, meta()).get();

        LlmInteractionEvent e = captured();
        assertFalse(e.isSuccess(), "LLMEvent.Error 事件必须记为失败，否则 run 挂了也查不到原因");
        assertEquals("rate limited by upstream", e.getErrorMessage());
    }

    @Test
    void eventError_afterTextDelta_stillRecordedAsFailure() throws Exception {
        // The provider may emit partial text before reporting an error; once it reports an error, the call did not succeed
        emitEvents(new LLMEvent.TextDelta("partial"), new LLMEvent.Error("stream broke"));

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), e -> {
                }, meta()).get();

        LlmInteractionEvent e = captured();
        assertFalse(e.isSuccess());
        assertEquals("stream broke", e.getErrorMessage());
    }

    @Test
    void normalTextTurn_isRecordedAsSuccess() throws Exception {
        emitEvents(new LLMEvent.TextDelta("hello"));

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), e -> {
                }, meta()).get();

        LlmInteractionEvent e = captured();
        assertTrue(e.isSuccess(), "正常回复必须记成功，否则会把成功调用也误标为失败");
        assertEquals(null, e.getErrorMessage());
    }

    @Test
    void toolCallOnlyTurn_isRecordedAsSuccessWithEmptyBody() throws Exception {
        // When only a tool call is returned with no text, response_body is naturally empty; that must not be misjudged as failure
        emitEvents(new LLMEvent.ToolCall("call-1", "builtin_5", "{}"));

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), e -> {
                }, meta()).get();

        LlmInteractionEvent e = captured();
        assertTrue(e.isSuccess(), "仅返回工具调用是正常一轮，不该记成失败");
        assertEquals(null, e.getErrorMessage());
    }

    @Test
    void providerThrows_isRecordedAsFailure() throws Exception {
        doAnswer(inv -> {
            throw new IllegalStateException("connection reset");
        }).when(modelProviderSpi).streamWithStats(anyString(), anyString(), any(), anyString(),
                any(ChatCompletionRequestDTO.class), any(), any());

        try {
            service.stream("c", "u", "k", "m",
                    new ChatCompletionRequestDTO(), e -> {
                    }, meta()).get();
        } catch (Exception expected) {
            // Exception path; only the persisted record matters
        }

        LlmInteractionEvent e = captured();
        assertFalse(e.isSuccess());
        assertTrue(e.getErrorMessage() != null && e.getErrorMessage().contains("connection reset"),
                "异常原因必须落库，实际=" + e.getErrorMessage());
    }

    @Test
    void errorEvent_isForwardedToCallerSoItCanFailTheTurn() throws Exception {
        emitEvents(new LLMEvent.Error("boom"));
        List<LLMEvent> seen = new java.util.ArrayList<>();

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), seen::add, meta()).get();

        assertTrue(seen.stream().anyMatch(ev -> ev instanceof LLMEvent.Error),
                "事件仍需转发给 SessionRunner —— 它靠这个置位 errorRef 并最终把 run 置 FAILED");

    }

    @Test
    void eventError_recordsUpstreamHttpStatus() throws Exception {
        emitEvents(new LLMEvent.Error("模型调用异常 (400): bad request", 400, false));

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), e -> {
                }, meta()).get();

        LlmInteractionEvent e = captured();
        assertFalse(e.isSuccess());
        assertEquals(400, e.getMeta().getHttpStatus().intValue(),
                "上游状态码必须落到交互记录，否则只能从 error_message 文本里猜");
    }

    @Test
    void normalTurn_recordsHttpStatus200() throws Exception {
        emitEvents(new LLMEvent.TextDelta("hello"));

        service.stream("c", "u", "k", "m",
                new ChatCompletionRequestDTO(), e -> {
                }, meta()).get();

        assertEquals(200, captured().getMeta().getHttpStatus().intValue(),
                "正常结束按 200 记，便于直接按状态码筛");
    }
}
