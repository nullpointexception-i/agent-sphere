package com.buukle.agent.model.service.adapter;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategyFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatRequestAdapter {

    private final ProviderStrategyFactory strategyFactory;

    public ChatRequestAdapter(ProviderStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    public void adapt(ChatCompletionRequestDTO request, String company) {
        ProviderStrategy strategy = strategyFactory.getStrategy(company);
        if (strategy != null) strategy.adaptRequest(request);
    }
}
