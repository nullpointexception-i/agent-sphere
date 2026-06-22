package com.buukle.agent.model.service.adapter.strategy;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.stream.ToolStream;
import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderStrategy {

    ModelProviderCompany getCompany();

    void adaptRequest(ChatCompletionRequestDTO request);

    void adaptResponse(JsonNode choice, ToolStream toolStream);

    ContentToolCallResult extractToolCallsFromContent(String content);
}
