package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 压缩预算必须使用最终请求的输出额度（实例优先、全局兜底），而不是固定比例。 */
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
}
