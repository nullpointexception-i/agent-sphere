package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
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

/** 压缩预算必须使用最终请求的输出额度（实例优先、全局兜底），而不是固定比例。 */
@ExtendWith(MockitoExtension.class)
class LlmRequestConfigurerTest {

    private static final String DEEPSEEK = "deepseek";
    private static final String GLM = "glm";

    @Mock
    SystemConfigSpi systemConfigSpi;

    private AgentRuntimeProperties properties;
    private LlmRequestConfigurer configurer;

    @BeforeEach
    void setUp() {
        properties = new AgentRuntimeProperties();
        configurer = new LlmRequestConfigurer(systemConfigSpi, properties);
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
    void dynamicBudgetFor16kLeavesHalfForAnswer() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertEquals(8000, request.getThinking().getBudgetTokens());
    }

    @Test
    void dynamicBudgetFor4kIsTwoThousand() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, 4000, DEEPSEEK);

        assertEquals(2000, request.getThinking().getBudgetTokens());
    }

    @Test
    void dynamicBudgetFor8kIsFourThousand() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, 8192, DEEPSEEK);

        assertEquals(4096, request.getThinking().getBudgetTokens());
    }

    @Test
    void dynamicBudgetIsCappedByMaxTokens() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, 32768, DEEPSEEK);

        assertEquals(8192, request.getThinking().getBudgetTokens());
    }

    @Test
    void tinyOutputCapSkipsBudget() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, 2000, DEEPSEEK);

        assertNull(request.getThinking().getBudgetTokens());
    }

    @Test
    void explicitBudgetWinsOverDynamicDefault() {
        ChatCompletionRequestDTO request = requestWithThinking(3000);

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertEquals(3000, request.getThinking().getBudgetTokens());
    }

    @Test
    void explicitBudgetAboveUpperBoundIsClamped() {
        ChatCompletionRequestDTO request = requestWithThinking(15000);

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertEquals(8192, request.getThinking().getBudgetTokens());
    }

    @Test
    void explicitZeroDisablesBudget() {
        ChatCompletionRequestDTO request = requestWithThinking(0);

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertNull(request.getThinking().getBudgetTokens());
    }

    @Test
    void disabledThinkingSkipsBudget() {
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                .setThinking(new ThinkingDTO().setType("disabled"));

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertNull(request.getThinking().getBudgetTokens());
    }

    @Test
    void providerOutsideAllowlistSkipsBudget() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, 16000, GLM);

        assertNull(request.getThinking().getBudgetTokens());
    }

    @Test
    void noOutputCapSkipsBudget() {
        ChatCompletionRequestDTO request = requestWithThinking();

        configurer.applyChat(request, null, null, DEEPSEEK);

        assertNull(request.getThinking().getBudgetTokens());
    }

    @Test
    void fallsBackToRequestMaxTokensWhenOutputCapMissing() {
        ChatCompletionRequestDTO request = requestWithThinking().setMaxTokens(16000);

        configurer.applyChat(request, null, null, DEEPSEEK);

        assertEquals(8000, request.getThinking().getBudgetTokens());
    }

    @Test
    void applyChatInjectsBudgetFromGlobalThinkingConfig() {
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn("{\"thinking\":true}");

        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertNotNull(request.getThinking());
        assertEquals(8000, request.getThinking().getBudgetTokens());
    }

    @Test
    void applyChatHonorsGlobalExplicitBudget() {
        when(systemConfigSpi.get(eq(SystemConfigKeys.LLM_DEFAULTS_CONFIG), anyString()))
                .thenReturn("{\"thinking\":true,\"budget_tokens\":3000}");

        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();

        configurer.applyChat(request, null, 16000, DEEPSEEK);

        assertEquals(3000, request.getThinking().getBudgetTokens());
    }

    private static ChatCompletionRequestDTO requestWithThinking() {
        return new ChatCompletionRequestDTO()
                .setThinking(new ThinkingDTO().setType("enabled"));
    }

    private static ChatCompletionRequestDTO requestWithThinking(Integer budget) {
        return new ChatCompletionRequestDTO()
                .setThinking(new ThinkingDTO().setType("enabled").setBudgetTokens(budget));
    }
}
