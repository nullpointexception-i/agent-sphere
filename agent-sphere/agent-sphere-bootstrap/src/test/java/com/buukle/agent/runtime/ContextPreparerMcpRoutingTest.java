package com.buukle.agent.runtime;

import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.capability.mcp.spi.McpHubSpi;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.capability.skill.spi.SkillHubSpi;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.runtime.orchestration.pipeline.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Local MCP tools and followed hub MCP tools must stay distinguishable at runtime: the executor
 * resolves local tools by {@code capability_mcp.id} and hub tools by {@code capability_mcp_hub.id}.
 */
class ContextPreparerMcpRoutingTest {

    private static final long LOCAL_MCP_ID = 42L;
    private static final long HUB_ID = 77L;
    private static final long HUB_ID_BASE = 1_000_000_000L;

    @Test
    void localMcpToolCarriesLocalTypeAndId() {
        KernelContext ctx = prepare("mcp", LOCAL_MCP_ID);

        RuntimeTool tool = ctx.getTools().stream()
                .filter(t -> t.getLlmToolName().startsWith("mcp_" + LOCAL_MCP_ID + "_"))
                .findFirst().orElse(null);
        assertNotNull(tool);
        assertEquals("mcp", tool.getCapabilityType());
        assertEquals(LOCAL_MCP_ID, tool.getCapabilityId());
    }

    @Test
    void hubMcpToolCarriesHubTypeAndId() {
        KernelContext ctx = prepare("mcp_hub", HUB_ID);

        RuntimeTool tool = ctx.getTools().stream()
                .filter(t -> t.getLlmToolName().startsWith("mcp_" + (HUB_ID_BASE + HUB_ID) + "_"))
                .findFirst().orElse(null);
        assertNotNull(tool);
        assertEquals("mcp_hub", tool.getCapabilityType());
        assertEquals(HUB_ID, tool.getCapabilityId());
    }

    private KernelContext prepare(String capabilityType, long capabilityId) {
        InstanceCapabilitySpi capabilitySpi = mock(InstanceCapabilitySpi.class);
        CapabilityVO cap = new CapabilityVO();
        cap.setId(1L);
        cap.setCapabilityType(capabilityType);
        cap.setCapabilityId(capabilityId);
        cap.setStatus("ENABLED");
        when(capabilitySpi.getCapabilitiesByInstance(anyLong())).thenReturn(List.of(cap));

        McpVO mcp = new McpVO();
        mcp.setId(capabilityId);
        mcp.setName("shared");
        mcp.setServerUrl("https://mcp.example.com/rpc");
        mcp.setServerType("http");
        mcp.setToolDefinitions("{}");
        mcp.setStatus("ENABLED");
        CapabilityMcpSpi mcpSpi = mock(CapabilityMcpSpi.class);
        when(mcpSpi.getMcp(capabilityId)).thenReturn(mcp);
        when(mcpSpi.listMcpTools(capabilityId)).thenReturn(List.of(tool()));

        McpHubSpi hubSpi = mock(McpHubSpi.class);
        when(hubSpi.getHubMcp(capabilityId)).thenReturn(mcp);
        when(hubSpi.listHubTools(capabilityId)).thenReturn(List.of(tool()));

        CapabilityBuiltinSpi builtin = mock(CapabilityBuiltinSpi.class);
        when(builtin.listAutoIncludeBuiltinTools()).thenReturn(List.of());
        when(builtin.listBuiltinTools()).thenReturn(List.of());

        ContextPreparer preparer = new ContextPreparer(capabilitySpi, mcpSpi, hubSpi,
                mock(CapabilitySkillSpi.class), mock(SkillHubSpi.class),
                mock(CapabilityCliSpi.class), builtin, new AgentRuntimeProperties());

        RunVO run = new RunVO();
        run.setId(1L);
        run.setSessionId(1L);
        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        return preparer.prepare(run, ValidationResult.builder().agentInstance(instance).build(), "hi");
    }

    private McpToolInfoVO tool() {
        return McpToolInfoVO.builder()
                .name("search")
                .description("search")
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }
}
