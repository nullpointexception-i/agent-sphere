package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.service.mcp.McpCallContext;
import com.buukle.agent.capability.mcp.service.mcp.McpProtocolConstants;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.service.ToolResultReadService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 任务级 MCP 凭证的传递链路。
 *
 * <p>这条链路一旦断了，Bole 侧就拿不到「哪个收藏者」，跨用户隔离直接失效 ——
 * 而且不会报错，只会表现为「A 用户能看到 B 用户收藏过谁」，属于最难被发现的一类故障。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorMcpCredentialTest {

    private static final Long SESSION_ID = 4242L;
    private static final String CREDENTIAL = "header.payload.signature";

    @Mock
    List<CapabilityMcpSpi> mcpSpis;
    @Mock
    CapabilityMcpSpi mcpSpi;
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
    PendingWaitSpi pendingWaitSpi;
    @Mock
    ToolResultReadService toolResultReadService;
    @Mock
    DelegateService delegateService;
    @Mock
    TaskMcpCredentialStore credentialStore;

    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolExecutor = new ToolExecutor(mcpSpis, builtinSpi, cliExecutorService, sessionTodoSpi,
                eventPublisher, clarificationSpi, pendingWaitSpi, delegateService, toolResultReadService, credentialStore);
        org.mockito.Mockito.lenient().when(mcpSpis.get(0)).thenReturn(mcpSpi);
    }

    private List<RuntimeTool> mcpTool() {
        return List.of(RuntimeTool.builder()
                .llmToolName("check_candidate_history")
                .capabilityType("mcp")
                .execBinding(Map.of(
                        ExecBindingKeys.MCP_SERVER_URL, "http://bole/api/v1/mcp/sourcing",
                        ExecBindingKeys.MCP_NATIVE_TOOL_NAME, "check_candidate_history"))
                .build());
    }

    @Test
    void credentialFromSessionStoreIsForwardedToMcpSpi() {
        org.mockito.Mockito.when(credentialStore.get(SESSION_ID)).thenReturn(CREDENTIAL);
        org.mockito.Mockito.when(mcpSpi.executeTool(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("{\"suggestion\":\"PROCEED\"}");

        toolExecutor.execute(new TurnToolCall("t1", "check_candidate_history", "{}"),
                SESSION_ID, 1L, mcpTool());

        verify(mcpSpi).executeTool(eq("http://bole/api/v1/mcp/sourcing"),
                eq("check_candidate_history"), eq("{}"), eq(CREDENTIAL));
    }

    @Test
    void callReasonIsStrippedBeforeMcpForwarding() {
        org.mockito.Mockito.when(credentialStore.get(SESSION_ID)).thenReturn(null);
        org.mockito.Mockito.when(mcpSpi.executeTool(anyString(), anyString(), anyString(), any()))
                .thenReturn("{}");

        toolExecutor.execute(new TurnToolCall("t1", "check_candidate_history",
                        "{\"candidateId\":7,\"_reason\":\"复核候选人\"}"),
                SESSION_ID, 1L, mcpTool());

        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(mcpSpi).executeTool(anyString(), anyString(), args.capture(), any());
        assertFalse(args.getValue().contains("_reason"),
                "保留参数不能发给 MCP server");
        assertTrue(args.getValue().contains("candidateId"));
    }

    @Test
    void missingCredentialBecomesNullInsteadOfEmptyString() {
        // Redis 里没有凭证时必须传 null：传空串会让服务端收到一个存在但无效的凭证头，
        // 掩盖「凭证链路断了」这个事实。
        org.mockito.Mockito.when(credentialStore.get(SESSION_ID)).thenReturn(null);
        org.mockito.Mockito.when(mcpSpi.executeTool(anyString(), anyString(), anyString(), any()))
                .thenReturn("{}");

        toolExecutor.execute(new TurnToolCall("t1", "check_candidate_history", "{}"),
                SESSION_ID, 1L, mcpTool());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mcpSpi).executeTool(anyString(), anyString(), anyString(), captor.capture());
        assertNull(captor.getValue());
    }

    @Test
    void callContextCarriesCredentialUnderFixedReservedHeader() {
        McpCallContext context = McpCallContext.ofTaskCredential(CREDENTIAL);

        assertEquals(CREDENTIAL, context.header(McpProtocolConstants.HEADER_TASK_MCP_CREDENTIAL));
        assertEquals("X-Task-Mcp-Credential", McpProtocolConstants.HEADER_TASK_MCP_CREDENTIAL);
        // 头名由 agent-sphere 固定，不可被任务提交方自定义，否则可覆盖已登记 MCP 的 Authorization。
        assertTrue(McpCallContext.ofTaskCredential(" ").isEmpty());
        assertTrue(McpCallContext.ofTaskCredential(null).isEmpty());
        assertTrue(McpCallContext.none().isEmpty());
    }
}
