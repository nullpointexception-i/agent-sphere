package com.buukle.agent.resource.template.init;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.service.InstanceService;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceInitializer implements ResourceInitializer {

    private static final String TYPE = "instance";

    private final InstanceSpi instanceSpi;
    private final InstanceService instanceService;
    private final CapabilityBuiltinSpi capabilityBuiltinSpi;
    private final InstanceCapabilitySpi instanceCapabilitySpi;
    private final RouteSpi routeSpi;

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
        InstanceVO match = instanceSpi.listInstances(name, null, null).stream()
                .filter(i -> name.equals(i.getName()))
                .findFirst()
                .orElse(null);
        if (match != null) {
            // Documents created later in the template need the instance id.
            ctx.put(TYPE, name, match.getId());
            throw new ResourceExistsException();
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName(name);
        dto.setDescription(descriptor.path("description").asText(null));
        dto.setSystemPrompt(descriptor.path("systemPrompt").asText(null));
        dto.setBusinessType(descriptor.path("businessType").asText(null));
        if (descriptor.hasNonNull("config")) {
            dto.setConfig(descriptor.path("config").toString());
        }
        ModelRouteVO defaultRoute = routeSpi.getDefaultRoute();
        if (defaultRoute != null) {
            dto.setModelRouteId(defaultRoute.getId());
        } else {
            String routeName = descriptor.path("route").asText(null);
            if (StringUtils.hasText(routeName)) {
                dto.setModelRouteId(ctx.get("model_route", routeName));
            }
        }
        InstanceVO vo = instanceService.createInstance(dto);
        ctx.put(TYPE, name, vo.getId());
        bindBuiltinBrowser(vo.getId());
    }

    /**
     * Binds the built-in browser tool to instances created from the template
     * (skipped when it is not registered; does not affect provisioning).
     */
    private void bindBuiltinBrowser(Long instanceId) {
        BuiltinToolVO chrome = capabilityBuiltinSpi.listBuiltinTools().stream()
                .filter(t -> t.getId() != null && t.getId().longValue() == BuiltinToolEnum.CHROME.getId())
                .findFirst()
                .orElse(null);
        if (chrome == null) {
            log.warn("Builtin chrome tool not registered, skip binding for instance {}", instanceId);
            return;
        }
        CreateInstanceCapabilityDTO dto = new CreateInstanceCapabilityDTO();
        dto.setInstanceId(instanceId);
        dto.setCapabilityType(InstanceCapabilityEnum.CAPABILITY_TYPE_BUILTIN);
        dto.setCapabilityId(chrome.getId());
        dto.setStatus(InstanceCapabilityEnum.STATUS_ENABLED);
        instanceCapabilitySpi.createCapability(dto);
    }
}
