package com.buukle.agent.model.service.adapter.strategy;

import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderStrategyFactory {

    private final Map<ModelProviderCompany, ProviderStrategy> strategyMap = new HashMap<>();

    public ProviderStrategyFactory(List<ProviderStrategy> strategies) {
        for (ProviderStrategy s : strategies) {
            ModelProviderCompany company = s.getCompany();
            if (company != null) {
                strategyMap.put(company, s);
            }
        }
    }

    public ProviderStrategy getStrategy(String company) {
        ModelProviderCompany provider = ModelProviderCompany.fromValue(company);
        if (provider == null) return null;
        return strategyMap.get(provider);
    }
}
