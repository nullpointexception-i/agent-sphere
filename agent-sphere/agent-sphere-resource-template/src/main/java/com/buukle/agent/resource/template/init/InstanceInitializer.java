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
        dto.setDescription(descriptor.path("description").asText(null));
        dto.setSystemPrompt(descriptor.path("systemPrompt").asText(null));
        dto.setBusinessType(descriptor.path("businessType").asText(null));
        String routeName = descriptor.path("route").asText(null);
        if (StringUtils.hasText(routeName)) {
            dto.setModelRouteId(ctx.get("model_route", routeName));
        }
        InstanceVO vo = instanceService.createInstance(dto);
        bindBuiltinBrowser(vo.getId());
    }

    /** 为模板创建的实例绑定内置浏览器工具（未注册时跳过，不影响开通）。 */
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
