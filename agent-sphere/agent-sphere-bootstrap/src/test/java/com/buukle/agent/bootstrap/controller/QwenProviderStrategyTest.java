package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategyFactory;
import com.buukle.agent.model.service.adapter.strategy.impl.OpenaiProviderStrategy;
import com.buukle.agent.model.service.adapter.strategy.impl.QwenProviderStrategy;
import com.buukle.agent.util.json.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qwen 策略：补齐 assistant 消息里「键必须存在」的字段。
 *
 * <p>线上 400 的原文：{@code messages[9] ... ChatCompletionMessageGenericParam ... 'content'
 * 'msg': 'Field required', 'input': {'role': 'assistant', 'tool_calls': [...]}} —— Qwen3 的
 * 网关要求 assistant 的 {@code content}（以及 thinking 场景下的 {@code reasoning_content}）字段
 * **必须存在**，而 {@code ChatMessageDTO} 的 {@code @JsonInclude(NON_NULL)} 会把 null 值整个省略。
 *
 * <p>所以策略只能填空串：填 null 会被 NON_NULL 再次省略，等于没改。
 */
class QwenProviderStrategyTest {

    private final QwenProviderStrategy qwen = new QwenProviderStrategy();

    private static ToolCallDTO toolCall() {
        return ToolCallDTO.builder()
                .id("call_e34062e46c9a4b1a9a897f03")
                .type("function")
                .function(FunctionDefinitionDTO.builder()
                        .name("builtin_5")
                        .arguments("{\"action\": \"navigate\", \"url\": \"https://www.baidu.com\"}")
                        .build())
                .build();
    }

    private static ChatMessageDTO assistantToolCall() {
        return new ChatMessageDTO().setRole("assistant").setToolCalls(List.of(toolCall()));
    }

    private static ChatCompletionRequestDTO request(ChatMessageDTO... messages) {
        ChatCompletionRequestDTO r = new ChatCompletionRequestDTO();
        r.setModel("Qwen3-8B");
        r.setMessages(new ArrayList<>(List.of(messages)));
        return r;
    }

    /** 复现线上请求体：只有 tool_calls 的 assistant 消息，content 键缺失。 */
    @Test
    void beforeAdapt_assistantToolCallOmitsContent() {
        String json = JsonUtils.toJson(request(assistantToolCall()));

        assertTrue(json.contains("\"tool_calls\""), json);
        assertFalse(json.contains("\"content\""),
                "前置条件：NON_NULL 会把 null 的 content 整个省略，这正是网关 400 的原因");
    }

    @Test
    void adaptRequest_addsContentAndReasoningContentAsEmptyStrings() {
        ChatCompletionRequestDTO req = request(assistantToolCall());

        qwen.adaptRequest(req);

        String json = JsonUtils.toJson(req);
        assertTrue(json.contains("\"content\":\"\""), "content 键必须出现（空串）：" + json);
        assertTrue(json.contains("\"reasoning_content\":\"\""),
                "thinking 多轮工具调用要求 reasoning_content 键存在：" + json);
    }

    @Test
    void adaptRequest_doesNotTouchPlainAssistantMessage() {
        ChatMessageDTO plain = new ChatMessageDTO().setRole("assistant").setContent("done");
        ChatCompletionRequestDTO req = request(plain);

        qwen.adaptRequest(req);

        assertEquals("done", plain.getContent(), "没有 tool_calls 的消息不应被改写");
        assertNull(plain.getReasoningContent(), "不应给非工具调用消息塞 reasoning_content");
    }

    @Test
    void adaptRequest_keepsExistingContentAndReasoning() {
        ChatMessageDTO m = assistantToolCall().setContent("thinking out loud").setReasoningContent("real reasoning");
        ChatCompletionRequestDTO req = request(m);

        qwen.adaptRequest(req);

        assertEquals("thinking out loud", m.getContent(), "已有内容不得被空串覆盖");
        assertEquals("real reasoning", m.getReasoningContent());
    }

    @Test
    void adaptRequest_stripsInternalThinkingFields() {
        ChatCompletionRequestDTO req = request(assistantToolCall());
        req.setThinking(new ThinkingDTO());

        qwen.adaptRequest(req);

        assertNull(req.getThinking(), "thinking 是本系统给智谱/DeepSeek 的内部字段，Qwen 不接受");
        assertNull(req.getToolStream());
    }

    /** 隔离性：其它策略不能跟着改动，否则等于全局改 DTO。 */
    @Test
    void otherStrategies_doNotFillContent() {
        ChatCompletionRequestDTO req = request(assistantToolCall());

        new OpenaiProviderStrategy().adaptRequest(req);

        assertFalse(JsonUtils.toJson(req).contains("\"content\""),
                "只有 Qwen 策略才补 content，不能影响其它厂商");
    }

    @Test
    void factory_resolvesQwenCaseInsensitively() {
        ProviderStrategyFactory factory = new ProviderStrategyFactory(
                List.of(new QwenProviderStrategy(), new OpenaiProviderStrategy()));

        assertNotNull(factory.getStrategy("qwen"));
        assertNotNull(factory.getStrategy("Qwen"));
        assertEquals(ModelProviderCompany.QWEN, factory.getStrategy("QWEN").getCompany());
        assertEquals(ModelProviderCompany.OPENAI, factory.getStrategy("openai").getCompany());
        assertNull(factory.getStrategy("unknown-vendor"));
    }
}
