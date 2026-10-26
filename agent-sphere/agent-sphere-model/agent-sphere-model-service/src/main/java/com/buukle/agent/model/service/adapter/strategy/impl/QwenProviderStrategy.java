package com.buukle.agent.model.service.adapter.strategy.impl;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.service.stream.ToolStream;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Qwen（通义千问）策略。
 *
 * <p>Qwen3 部署（vLLM / PAI-EAS 网关）对入参做**严格** schema 校验：assistant 消息的
 * {@code content}、{@code reasoning_content} 都是「键必须存在」的字段（值可为空串）。
 * 而 {@code ChatMessageDTO} 是类级 {@code @JsonInclude(NON_NULL)} —— 只带 tool_calls 的
 * assistant 消息因为 content 为 null，序列化时整个键被省略，网关直接 400：
 *
 * <pre>
 * {'type':'missing','loc':('body','messages',9,...,'content'),'msg':'Field required'}
 * </pre>
 *
 * <p><b>本策略只能用空串补齐，不能用 null</b>：NON_NULL 会把 null 再次省略，填 null 等于没改。
 */
@Component
public class QwenProviderStrategy implements ProviderStrategy {

    private static final String ROLE_ASSISTANT = "assistant";

    @Override
    public ModelProviderCompany getCompany() {
        return ModelProviderCompany.QWEN;
    }

    @Override
    public void adaptRequest(ChatCompletionRequestDTO request) {
        // Qwen3 是 OpenAI 兼容接口；thinking / tool_stream 是本系统给智谱/DeepSeek 用的内部字段
        request.setThinking(null);
        request.setToolStream(null);

        List<ChatMessageDTO> messages = request.getMessages();
        if (messages == null) {
            return;
        }
        for (ChatMessageDTO m : messages) {
            if (m == null || !ROLE_ASSISTANT.equals(m.getRole())) {
                continue;
            }
            boolean hasToolCalls = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
            if (!hasToolCalls) {
                continue;
            }
            // content 键必须存在（空串即可满足；null 会被 NON_NULL 省略）
            if (m.getContent() == null) {
                m.setContent("");
            }
            // Qwen3 开启 thinking 时，多轮工具调用要求回传 assistant 的 reasoning_content。
            // 消息层拿不到原始推理文本，这里只保证「键存在」——空串即可让模板正常读取。
            if (m.getReasoningContent() == null) {
                m.setReasoningContent("");
            }
        }
    }

    @Override
    public void adaptResponse(JsonNode choice, ToolStream toolStream) {
        parseDeltaToolCalls(choice, toolStream);
    }

    @Override
    public ContentToolCallResult extractToolCallsFromContent(String content) {
        return ContentToolCallResult.EMPTY;
    }

    /** 与 OpenAI 兼容流式解析一致（Qwen3 的 SSE delta 结构相同）。 */
    private void parseDeltaToolCalls(JsonNode choice, ToolStream toolStream) {
        if (!choice.has(LlmApiConstants.FIELD_DELTA)) {
            return;
        }
        JsonNode delta = choice.get(LlmApiConstants.FIELD_DELTA);
        if (delta.has(LlmApiConstants.FIELD_TOOL_CALLS)
                && delta.get(LlmApiConstants.FIELD_TOOL_CALLS).isArray()) {
            for (JsonNode tc : delta.get(LlmApiConstants.FIELD_TOOL_CALLS)) {
                int index = tc.has(LlmApiConstants.FIELD_INDEX)
                        ? tc.get(LlmApiConstants.FIELD_INDEX).asInt() : 0;
                String tcId = tc.has(LlmApiConstants.FIELD_ID)
                        ? tc.get(LlmApiConstants.FIELD_ID).asText() : null;
                String tcName = null;
                String tcArgs = null;
                if (tc.has(LlmApiConstants.FIELD_FUNCTION)) {
                    JsonNode fn = tc.get(LlmApiConstants.FIELD_FUNCTION);
                    if (fn.has(LlmApiConstants.FIELD_NAME) && !fn.get(LlmApiConstants.FIELD_NAME).isNull()) {
                        tcName = fn.get(LlmApiConstants.FIELD_NAME).asText();
                    }
                    if (fn.has(LlmApiConstants.FIELD_ARGUMENTS)) {
                        tcArgs = fn.get(LlmApiConstants.FIELD_ARGUMENTS).asText();
                    }
                }
                toolStream.onDelta(index, tcId, tcName, tcArgs);
            }
        }
    }
}
