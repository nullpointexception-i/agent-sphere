package com.buukle.agent.model.service.adapter;

import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategyFactory;
import com.buukle.agent.model.service.stream.ToolStream;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class ChatResponseAdapter {

    private final ProviderStrategyFactory strategyFactory;

    public ChatResponseAdapter(ProviderStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    public void adaptResponse(JsonNode choice, ToolStream toolStream, String company) {
        ProviderStrategy strategy = strategyFactory.getStrategy(company);
        if (strategy != null) strategy.adaptResponse(choice, toolStream);
    }

    public ContentToolCallResult extractToolCalls(String content, String company) {
        ProviderStrategy strategy = strategyFactory.getStrategy(company);
        if (strategy == null) return ContentToolCallResult.EMPTY;
        return strategy.extractToolCallsFromContent(content);
    }
}
