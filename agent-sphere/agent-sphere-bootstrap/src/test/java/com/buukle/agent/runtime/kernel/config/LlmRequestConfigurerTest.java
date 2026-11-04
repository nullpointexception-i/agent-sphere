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
 * 采样配置接线：实例优先、全局兜底；思考强度由 reasoning_effort 控制，不再生成 budget_tokens。
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
        // 存量实例配置里的 budget_tokens 现在应被忽略，但其余字段仍然生效。
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn("{\"temperature\":0.2,\"thinking\":true,\"budget_tokens\":8192}");

        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();
        configurer.applyChat(request, "{\"temperature\":0.5}");

        assertEquals(0.5, request.getTemperature());
        assertNotNull(request.getThinking());
        assertEquals("enabled", request.getThinking().getType());
    }
}
