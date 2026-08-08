package com.buukle.agent.resource.template.init;

import com.buukle.agent.model.dtvo.dto.CreateApiKeyDTO;
import com.buukle.agent.model.dtvo.vo.ApiKeyVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ApiKeyInitializer implements ResourceInitializer {

    private static final String TYPE = "api_key";

    private final ApiKeySpi apiKeySpi;
    private final ModelProviderSpi modelProviderSpi;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void initialize(JsonNode descriptor, ResourceInitContext ctx) {
        String providerName = descriptor.path("provider").asText();
        Long providerId = ctx.get("model_provider", providerName);
        if (providerId == null) {
            return;
        }
        String alias = descriptor.path("alias").asText("default-key");
        CreateApiKeyDTO dto = new CreateApiKeyDTO();
        dto.setProviderId(providerId);
        dto.setAlias(alias);
        dto.setKeyValue(mockKey());
        ApiKeyVO vo = apiKeySpi.createApiKey(dto);
        modelProviderSpi.setActiveKey(providerId, vo.getId());
        ctx.put(TYPE, alias, vo.getId());
    }

    private static String mockKey() {
        return "mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
