package com.buukle.agent.resource.template.init;

import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.service.InstanceService;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class InstanceInitializer implements ResourceInitializer {

    private static final String TYPE = "instance";

    private final InstanceSpi instanceSpi;
    private final InstanceService instanceService;

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
        boolean exists = instanceSpi.listInstances(name, null, null).stream()
                .anyMatch(i -> name.equals(i.getName()));
        if (exists) {
            throw new ResourceExistsException();
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName(name);
        dto.setBusinessType(descriptor.path("businessType").asText(null));
        String routeName = descriptor.path("route").asText(null);
        if (StringUtils.hasText(routeName)) {
            dto.setModelRouteId(ctx.get("model_route", routeName));
        }
        instanceService.createInstance(dto);
    }
}
