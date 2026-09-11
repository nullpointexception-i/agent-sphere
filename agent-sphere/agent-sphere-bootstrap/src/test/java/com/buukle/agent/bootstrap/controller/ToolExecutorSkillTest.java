package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ToolExecutorSkillTest {

    @Mock
    List<CapabilityMcpSpi> mcpSpis;
    @Mock
    CapabilityBuiltinSpi builtinSpi;
    @Mock
    CliExecutorService cliExecutorService;
    @Mock
    SessionTodoSpi sessionTodoSpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    ClarificationSpi clarificationSpi;
    @Mock
    DelegateService delegateService;

    ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolExecutor = new ToolExecutor(mcpSpis, builtinSpi, cliExecutorService, sessionTodoSpi,
                eventPublisher, clarificationSpi, delegateService);
    }

    private RuntimeTool delegateTool() {
        return RuntimeTool.builder()
                .capabilityType(SubAgentConstants.CAPABILITY_TYPE_SUB_AGENT)
                .llmToolName(SubAgentConstants.DELEGATE_TOOL)
                .toolRef(ToolRefs.agent(SubAgentConstants.DELEGATE_TOOL))
                .execBinding(java.util.Map.of())
                .build();
    }

    @Test
    void delegate_dispatchesToDelegateService() {
        given(delegateService.execute(anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("{\"mode\":\"main\"}");

        String result = toolExecutor.execute(
                new TurnToolCall("call_9", SubAgentConstants.DELEGATE_TOOL, "{\"goal\":\"g\",\"mode\":\"main\"}"),
                SubRunExecutionContext.root(1L, 2L, null),
                List.of(delegateTool()));

        assertEquals("{\"mode\":\"main\"}", result);
        verify(delegateService).execute(anyString(), any(SubRunExecutionContext.class), anyList());
    }

    @Test
    void unknownTool_returnsError() {
        String result = toolExecutor.execute(
                new TurnToolCall("call_1", "nope", "{}"),
                SubRunExecutionContext.root(1L, 2L, null),
                List.of());
        assertTrue(result.contains("Unknown tool"));
    }

    @Test
    void isSubRunTool_onlyForDelegate() {
        List<RuntimeTool> tools = List.of(delegateTool(),
                RuntimeTool.builder().llmToolName("builtin_1").toolRef("builtin:x").build());
        assertTrue(toolExecutor.isSubRunTool(SubAgentConstants.DELEGATE_TOOL, tools));
        assertFalse(toolExecutor.isSubRunTool("builtin_1", tools));
    }
}
