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
 * Propagation chain for task-level MCP credentials.
 *
 * <p>If this chain breaks, the Bole side cannot tell "which collector", and cross-user isolation fails outright —
 * and it raises no error, only "user A can see whom user B collected", one of the hardest classes of bugs to notice.
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
        // With no credential in Redis, null must be passed: an empty string makes the server receive a present-but-invalid credential header,
        // masking the fact that "the credential chain is broken".
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
        // The header name is fixed by agent-sphere and cannot be customized by the task submitter, otherwise it could override the registered MCP Authorization.
        assertTrue(McpCallContext.ofTaskCredential(" ").isEmpty());
        assertTrue(McpCallContext.ofTaskCredential(null).isEmpty());
        assertTrue(McpCallContext.none().isEmpty());
    }
}
