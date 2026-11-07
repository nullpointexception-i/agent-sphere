package com.buukle.agent.model.service.adapter.strategy.impl;

import com.buukle.agent.common.constant.ReasoningEffort;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.service.stream.ToolStream;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class DeepseekProviderStrategy implements ProviderStrategy {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String THINKING_ENABLED = "enabled";
    private static final String THINKING_DISABLED = "disabled";

    @Override
    public ModelProviderCompany getCompany() {
        return ModelProviderCompany.DEEPSEEK;
    }

    @Override
    public void adaptRequest(ChatCompletionRequestDTO request) {
        // DeepSeek: reject fields it is known not to support, avoiding 400s/silent failures.
        // Keep stream_options (usage collection), user, parallel_tool_calls (OpenAI-compatible default behavior).
        request.setSeed(null);
        request.setN(null);
        request.setMaxCompletionTokens(null);
        request.setLogprobs(null);
        request.setTopLogprobs(null);
        request.setLogitBias(null);
        // enable_thinking is a Qwen-only field; DeepSeek uses thinking.type + reasoning_effort.
        request.setEnableThinking(null);
        // Unified effort tier → DeepSeek thinking switch: none turns it off, anything else turns it on;
        // the concrete strength is expressed by reasoning_effort.
        if (request.getReasoningEffort() != null) {
            boolean off = ReasoningEffort.NONE.equalsIgnoreCase(request.getReasoningEffort());
            request.setThinking(new ThinkingDTO().setType(off ? THINKING_DISABLED : THINKING_ENABLED));
        }
        // DeepSeek thinking + tools: assistant.reasoning_content of previous turns must be passed
        // back. The key must exist on EVERY assistant turn, not only tool-call turns: DeepSeek
        // validates the assistant messages after the last user message, and a rebuilt history
        // (restart recovery / resume) can move a content-only assistant into that window — an
        // omitted key then fails the whole request with 400. ChatMessageDTO is @JsonInclude(NON_NULL),
        // so the key only appears when the value is a non-null string: fill "" wherever it is missing.
        if (request.getMessages() != null) {
            for (ChatMessageDTO m : request.getMessages()) {
                if (m == null || !ROLE_ASSISTANT.equals(m.getRole())) {
                    continue;
                }
                if (m.getReasoningContent() == null) {
                    m.setReasoningContent("");
                }
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

    private void parseDeltaToolCalls(JsonNode choice, ToolStream toolStream) {
        if (!choice.has(LlmApiConstants.FIELD_DELTA)) return;
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
                    if (fn.has(LlmApiConstants.FIELD_NAME) && !fn.get(LlmApiConstants.FIELD_NAME).isNull())
                        tcName = fn.get(LlmApiConstants.FIELD_NAME).asText();
                    if (fn.has(LlmApiConstants.FIELD_ARGUMENTS))
                        tcArgs = fn.get(LlmApiConstants.FIELD_ARGUMENTS).asText();
                }
                toolStream.onDelta(index, tcId, tcName, tcArgs);
            }
        }
    }
}
