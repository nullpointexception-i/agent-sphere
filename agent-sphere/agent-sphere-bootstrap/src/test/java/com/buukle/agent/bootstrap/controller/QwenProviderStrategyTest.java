package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.FunctionDefinitionDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategyFactory;
import com.buukle.agent.model.service.adapter.strategy.impl.DeepseekProviderStrategy;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void openaiStrategy_stripsReasoningContentFromAllMessages() {
        ChatMessageDTO assistant = assistantToolCall().setReasoningContent("private reasoning");
        ChatMessageDTO reply = new ChatMessageDTO().setRole("assistant")
                .setContent("done").setReasoningContent("final reasoning");
        ChatCompletionRequestDTO req = request(assistant, reply);

        new OpenaiProviderStrategy().adaptRequest(req);

        assertNull(assistant.getReasoningContent(), "OpenAI 不接受 reasoning_content");
        assertNull(reply.getReasoningContent());
        assertFalse(JsonUtils.toJson(req).contains("reasoning_content"));
    }

    @Test
    void deepseekStrategy_keepsReasoningAndFillsMissingKeyOnToolTurns() {
        ChatMessageDTO withReasoning = assistantToolCall().setReasoningContent("real reasoning");
        ChatMessageDTO withoutReasoning = assistantToolCall();
        ChatMessageDTO plain = new ChatMessageDTO().setRole("assistant").setContent("done");
        ChatCompletionRequestDTO req = request(withReasoning, withoutReasoning, plain);

        new DeepseekProviderStrategy().adaptRequest(req);

        assertEquals("real reasoning", withReasoning.getReasoningContent());
        assertEquals("", withoutReasoning.getReasoningContent(), "工具轮必须带 reasoning_content 键");
        assertNull(plain.getReasoningContent(), "非工具轮不需要补 reasoning_content");
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

    // ---- system 消息位置：Qwen3 要求 system 必须在最前，runner 的尾部 system 需降级为 user ----

    @Test
    void adaptRequest_demotesTrailingSystemMessagesToUserPreservingPosition() {
        ChatMessageDTO base = new ChatMessageDTO().setRole("system").setContent("base prompt");
        ChatMessageDTO user = new ChatMessageDTO().setRole("user").setContent("hi");
        ChatMessageDTO assistant = new ChatMessageDTO().setRole("assistant").setContent("hello");
        ChatMessageDTO time = new ChatMessageDTO().setRole("system").setContent("trailing system fixture: t");
        ChatMessageDTO budget = new ChatMessageDTO().setRole("system").setContent("[LOOP BUDGET] Loop 1/300");
        ChatCompletionRequestDTO req = request(base, user, assistant, time, budget);

        qwen.adaptRequest(req);

        List<ChatMessageDTO> m = req.getMessages();
        assertEquals(5, m.size());
        assertEquals("system", m.get(0).getRole(), "首条 system 必须保留");
        assertEquals("base prompt", m.get(0).getContent());
        assertEquals("user", m.get(1).getRole());
        assertEquals("assistant", m.get(2).getRole());
        assertEquals("user", m.get(3).getRole(), "尾部 system 必须降级为 user");
        assertEquals("trailing system fixture: t", m.get(3).getContent());
        assertEquals("user", m.get(4).getRole());
        assertEquals("[LOOP BUDGET] Loop 1/300", m.get(4).getContent());
        // 不能原地改：request 的 messages 与 runner 历史共享元素
        assertEquals("system", time.getRole(), "原对象不得被污染（runner 历史共享同一实例）");
        assertEquals("system", budget.getRole());
    }

    @Test
    void adaptRequest_keepsLeadingOnlySystemUnchanged() {
        ChatMessageDTO base = new ChatMessageDTO().setRole("system").setContent("base prompt");
        ChatMessageDTO user = new ChatMessageDTO().setRole("user").setContent("hi");
        ChatCompletionRequestDTO req = request(base, user);

        qwen.adaptRequest(req);

        List<ChatMessageDTO> m = req.getMessages();
        assertEquals("system", m.get(0).getRole());
        assertEquals("user", m.get(1).getRole());
        assertSame(base, m.get(0), "没有尾部 system 时不应重建列表");
    }

    @Test
    void adaptRequest_noSystemMessageUnchanged() {
        ChatMessageDTO user = new ChatMessageDTO().setRole("user").setContent("hi");
        ChatCompletionRequestDTO req = request(user);

        qwen.adaptRequest(req);

        assertEquals("user", req.getMessages().get(0).getRole());
    }

    // ---- leading system：HistoryLoader 的取消提示/压缩摘要 + 系统提示词 → 必须合并成一条 ----

    @Test
    void adaptRequest_mergesLeadingSystemMessagesIntoOne() {
        ChatMessageDTO prompt = new ChatMessageDTO().setRole("system").setContent("base prompt");
        ChatMessageDTO hint = new ChatMessageDTO().setRole("system")
                .setContent("[The previous run was cancelled.]");
        ChatMessageDTO user = new ChatMessageDTO().setRole("user").setContent("hi");
        ChatCompletionRequestDTO req = request(prompt, hint, user);

        qwen.adaptRequest(req);

        List<ChatMessageDTO> m = req.getMessages();
        assertEquals(2, m.size(), "两条 leading system 必须合并为一条");
        assertEquals("system", m.get(0).getRole());
        assertEquals("base prompt\n\n[The previous run was cancelled.]", m.get(0).getContent());
        assertEquals("user", m.get(1).getRole());
        assertEquals("system", prompt.getRole(), "原对象不得被污染");
        assertEquals("system", hint.getRole());
    }

    @Test
    void adaptRequest_mergesLeadingSystemAndDemotesTrailingSystem() {
        ChatMessageDTO prompt = new ChatMessageDTO().setRole("system").setContent("base prompt");
        ChatMessageDTO hint = new ChatMessageDTO().setRole("system").setContent("cancelled hint");
        ChatMessageDTO user = new ChatMessageDTO().setRole("user").setContent("hi");
        ChatMessageDTO assistant = new ChatMessageDTO().setRole("assistant").setContent("hello");
        ChatMessageDTO time = new ChatMessageDTO().setRole("system").setContent("trailing system fixture: t");
        ChatCompletionRequestDTO req = request(prompt, hint, user, assistant, time);

        qwen.adaptRequest(req);

        List<ChatMessageDTO> m = req.getMessages();
        assertEquals(4, m.size());
        assertEquals("system", m.get(0).getRole());
        assertEquals("base prompt\n\ncancelled hint", m.get(0).getContent());
        assertEquals("user", m.get(1).getRole());
        assertEquals("assistant", m.get(2).getRole());
        assertEquals("user", m.get(3).getRole(), "尾部 system 仍要降级为 user");
        assertEquals("trailing system fixture: t", m.get(3).getContent());
    }
}
