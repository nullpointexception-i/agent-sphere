package com.buukle.agent.resource.template.init;

import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class McpInitializer implements ResourceInitializer {

    private static final String TYPE = "mcp";

    private final CapabilityMcpSpi mcpSpi;

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
        boolean exists = mcpSpi.listMcps(name, null, null).stream()
                .anyMatch(m -> name.equals(m.getName()));
        if (exists) {
            throw new ResourceExistsException();
        }
        CreateMcpDTO dto = new CreateMcpDTO();
        dto.setName(name);
        dto.setServerUrl(descriptor.path("serverUrl").asText(null));
        dto.setServerType(descriptor.path("serverType").asText("stdio"));
        mcpSpi.createMcp(dto);
    }
}
