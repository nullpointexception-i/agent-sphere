package com.buukle.agent.runtime.kernel.model.invoke;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
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
 * LLM 调用记录的失败判定护栏。
 *
 * <p>线上曾出现整批 run 被「模型端秒回错误」打挂，而 {@code agent_llm_interaction_record} 里
 * 这些记录<b>全是 success=true / error_message=NULL</b> —— 因为供应商是以 {@link LLMEvent.Error}
 * <b>事件</b>（而非异常）回报错误的，该路径根本不经过 catch。事后只能靠「耗时&lt;1s + 零 token」
 * 反推，错误原因彻底丢失。本测试锁死「事件错误也必须记为失败」。
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
        service = new KernelLlmService(modelProviderSpi, eventPublisher, props);
    }

    /** 让供应商在 stream 过程中发一个事件序列（不抛异常）。 */
    private void emitEvents(LLMEvent... events) throws Exception {
        doAnswer(inv -> {
            Consumer<LLMEvent> consumer = inv.getArgument(5);
            Runnable done = inv.getArgument(6);
            for (LLMEvent e : events) {
                consumer.accept(e);
            }
            done.run();
            return null;
        }).when(modelProviderSpi).stream(anyString(), anyString(), any(), anyString(),
                any(ChatCompletionRequestDTO.class), any(), any());
    }

    private LlmInteractionEvent captured() throws Exception {
        ArgumentCaptor<LlmInteractionEvent> cap = ArgumentCaptor.forClass(LlmInteractionEvent.class);
        // 交互记录在 finally 里发布，而 future 在 finally <b>之前</b> 就 complete 了，
        // 所以 get() 返回时事件未必已发出 —— 必须带超时等待。
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
        // 供应商可能先吐部分文本再报错；只要报了错，这次调用就没成功
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
        // 只回 tool call 不回正文时 response_body 本就是空串，属正常；不能因此误判为失败
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
        }).when(modelProviderSpi).stream(anyString(), anyString(), any(), anyString(),
                any(ChatCompletionRequestDTO.class), any(), any());

        try {
            service.stream("c", "u", "k", "m",
                    new ChatCompletionRequestDTO(), e -> {
                    }, meta()).get();
        } catch (Exception expected) {
            // 异常路径，只关心落库记录
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
