package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Sampling config wiring: instance first, global as fallback; reasoning effort is controlled by reasoning_effort and budget_tokens is no longer generated.
 */
@ExtendWith(MockitoExtension.class)
class LlmRequestConfigurerTest {

    @Mock
    SystemConfigSpi systemConfigSpi;

    private LlmRequestConfigurer configurer;

    @BeforeEach
    void setUp() {
        configurer = new LlmRequestConfigurer(systemConfigSpi);
    }

    @Test
    void instanceMaxTokensOverridesGlobal() {
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn("{\"max_tokens\":4096}");

        assertEquals(4096, configurer.resolveMaxOutputTokens(null));
        assertEquals(2048, configurer.resolveMaxOutputTokens("{\"max_tokens\":2048}"));
    }

    @Test
    void fallsBackToMaxCompletionTokens() {
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn(null);

        assertEquals(8192, configurer.resolveMaxOutputTokens("{\"max_completion_tokens\":8192}"));
    }

    @Test
    void returnsNullWhenNoCapConfigured() {
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn(null);

        assertNull(configurer.resolveMaxOutputTokens(null));
    }

    @Test
    void applyChatKeepsThinkingButNeverAddsBudget() {
        // budget_tokens in legacy instance config should now be ignored, while the other fields still apply.
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn("{\"temperature\":0.2,\"thinking\":true,\"budget_tokens\":8192}");

        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();
        configurer.applyChat(request, "{\"temperature\":0.5}");

        assertEquals(0.5, request.getTemperature());
        assertNotNull(request.getThinking());
        assertEquals("enabled", request.getThinking().getType());
    }
}
