package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class KernelLlmServiceTest {

    @Mock
    ModelProviderSpi modelProviderSpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    AgentRuntimeProperties properties;

    KernelLlmService service;

    @BeforeEach
    void setUp() {
        service = new KernelLlmService(modelProviderSpi, eventPublisher, properties);
    }

    @Test
    void invokeSync_timeout_shouldReturnFailedResultNotThrow() {
        // stream mock never invokes onDone -> invokeSync must time out and return a failed result
        KernelLlmService.InvokeResult result = service.invokeSync(
                "openai", "https://api.openai.com/v1", "sk-test", "gpt-4o",
                new ChatCompletionRequestDTO(), new LlmInteractionMeta(), 1);

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        assertEquals("", result.getContent());
    }

    @Test
    void invokeSync_syncThrow_shouldReturnFailedResultNotThrow() {
        org.mockito.BDDMockito.willThrow(new RuntimeException("boom"))
                .given(modelProviderSpi).stream(any(), any(), any(), any(), any(), any(), any());

        KernelLlmService.InvokeResult result = service.invokeSync(
                "openai", "https://api.openai.com/v1", "sk-test", "gpt-4o",
                new ChatCompletionRequestDTO(), new LlmInteractionMeta(), 5);

        assertFalse(result.isSuccess());
        assertEquals("boom", result.getError());
    }

    @Test
    void invokeSync_success_shouldCollectContentAndUsage() {
        org.mockito.BDDMockito.willAnswer(inv -> {
                    var onEvent = inv.getArgument(5, java.util.function.Consumer.class);
                    var onDone = inv.getArgument(6, Runnable.class);
                    onEvent.accept(new com.buukle.agent.model.dtvo.complete.LLMEvent.TextDelta("你好"));
                    onEvent.accept(new com.buukle.agent.model.dtvo.complete.LLMEvent.Finish("stop", java.util.Map.of("total_tokens", 5)));
                    onDone.run();
                    return null;
                })
                .given(modelProviderSpi).stream(any(), any(), any(), any(), any(), any(), any());

        KernelLlmService.InvokeResult result = service.invokeSync(
                "openai", "https://api.openai.com/v1", "sk-test", "gpt-4o",
                new ChatCompletionRequestDTO(), new LlmInteractionMeta(), 5);

        assertTrue(result.isSuccess());
        assertEquals("你好", result.getContent());
        assertEquals(5, result.getUsage().get("total_tokens"));
    }
}
