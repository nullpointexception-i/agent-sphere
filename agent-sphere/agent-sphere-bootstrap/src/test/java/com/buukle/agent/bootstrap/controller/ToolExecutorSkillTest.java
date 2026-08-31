package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.SkillExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.skill.SkillReActExecutor;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
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
    ObjectProvider<SkillReActExecutor> skillExecutorProvider;
    @Mock
    SkillReActExecutor skillReActExecutor;

    ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolExecutor = new ToolExecutor(mcpSpis, builtinSpi, cliExecutorService, sessionTodoSpi,
                eventPublisher, clarificationSpi, skillExecutorProvider);
    }

    @Test
    void skillBranch_delegatesToSkillReActExecutor() {
        RuntimeTool skillTool = RuntimeTool.builder()
                .capabilityType("skill")
                .capabilityId(8L)
                .llmToolName("skill_8")
                .toolRef("skill:8")
                .parametersSchemaJson("{\"type\":\"object\"}")
                .execBinding(Map.of(
                        ExecBindingKeys.SKILL_PROMPT_TEMPLATE, "请按配置执行",
                        ExecBindingKeys.SKILL_ALLOW_TOOLS, List.of()))
                .build();
        doReturn(skillReActExecutor).when(skillExecutorProvider).getIfAvailable();
        given(skillReActExecutor.execute(any(RuntimeTool.class), anyString(),
                any(SkillExecutionContext.class), anyList())).willReturn("{\"result\":\"ok\"}");

        String result = toolExecutor.execute(
                new TurnToolCall("call_1", "skill_8", "{\"keyword\":\"x\"}"),
                SkillExecutionContext.root(1L, 2L, null),
                List.of(skillTool));

        assertEquals("{\"result\":\"ok\"}", result);
        verify(skillReActExecutor).execute(any(RuntimeTool.class), anyString(),
                any(SkillExecutionContext.class), anyList());
    }

    @Test
    void unknownTool_returnsError() {
        String result = toolExecutor.execute(
                new TurnToolCall("call_1", "nope", "{}"),
                SkillExecutionContext.root(1L, 2L, null),
                List.of());
        assertTrue(result.contains("Unknown tool"));
    }
}