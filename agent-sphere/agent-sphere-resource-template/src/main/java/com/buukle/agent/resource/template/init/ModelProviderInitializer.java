package com.buukle.agent.resource.template.init;

import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ModelProviderInitializer implements ResourceInitializer {

    private static final String TYPE = "model_provider";

    private final ModelProviderSpi modelProviderSpi;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void initialize(JsonNode descriptor, ResourceInitContext ctx) {
        String name = descriptor.path("name").asText();
        if (!StringUtils.hasText(name)) {
            return;
        }
        List<ModelProviderVO> existing = modelProviderSpi.listProviders(name);
        if (existing.stream().anyMatch(p -> name.equals(p.getName()))) {
            throw new ResourceExistsException();
        }
        CreateModelProviderDTO dto = new CreateModelProviderDTO();
        dto.setName(name);
        dto.setBaseUrl(descriptor.path("baseUrl").asText(null));
        ModelProviderVO vo = modelProviderSpi.createProvider(dto);
        ctx.put(TYPE, name, vo.getId());
    }
}
