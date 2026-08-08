package com.buukle.agent.resource.template.init;

import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RouteInitializer implements ResourceInitializer {

    private static final String TYPE = "model_route";

    private final RouteSpi routeSpi;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void initialize(JsonNode descriptor, ResourceInitContext ctx) {
        String providerName = descriptor.path("provider").asText();
        Long providerId = ctx.get("model_provider", providerName);
        String modelName = descriptor.path("modelName").asText();
        if (providerId == null || !StringUtils.hasText(modelName)) {
            return;
        }
        boolean exists = routeSpi.listRoutesByProvider(providerId, null).stream()
                .anyMatch(r -> modelName.equals(r.getModelName()));
        if (exists) {
            throw new ResourceExistsException();
        }
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(providerId);
        dto.setModelName(modelName);
        dto.setCompany(descriptor.path("company").asText("deepseek"));
        dto.setWeight(descriptor.path("weight").asInt(100));
        ModelRouteVO vo = routeSpi.createRoute(dto);
        ctx.put(TYPE, modelName, vo.getId());
    }
}
