package com.buukle.agent.model.service.adapter.strategy.impl;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.service.stream.ToolStream;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class ZhipuProviderStrategy implements ProviderStrategy {

    @Override
    public ModelProviderCompany getCompany() {
        return ModelProviderCompany.GLM;
    }

    @Override
    public void adaptRequest(ChatCompletionRequestDTO request) {
        request.setToolStream(true);
    }

    @Override
    public void adaptResponse(JsonNode choice, ToolStream toolStream) {
        parseDeltaToolCalls(choice, toolStream);
        parseMessageToolCalls(choice, toolStream);
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

    private void parseMessageToolCalls(JsonNode choice, ToolStream toolStream) {
        if (!choice.has(LlmApiConstants.FIELD_MESSAGE)) return;
        JsonNode msg = choice.get(LlmApiConstants.FIELD_MESSAGE);
        if (msg.has(LlmApiConstants.FIELD_TOOL_CALLS)
                && msg.get(LlmApiConstants.FIELD_TOOL_CALLS).isArray()) {
            for (JsonNode tc : msg.get(LlmApiConstants.FIELD_TOOL_CALLS)) {
                int index = tc.has(LlmApiConstants.FIELD_INDEX)
                        ? tc.get(LlmApiConstants.FIELD_INDEX).asInt() : 0;
                String tcId = tc.has(LlmApiConstants.FIELD_ID)
                        ? tc.get(LlmApiConstants.FIELD_ID).asText()
                        : "msg_" + System.nanoTime();
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
