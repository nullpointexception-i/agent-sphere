package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.service.ToolResultReadService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Suspension-resume contract of the wait tool.
 *
 * <p>There is only one core invariant, and it is easily broken: <b>wait must never block</b>.
 * Sleeping here would hold a maxParallel permit and hit tool.execution-timeout,
 * making "wait longer than 60s" unsatisfiable again — which is exactly why this rework exists.
 */
@ExtendWith(MockitoExtension.class)
class WaitBuiltinToolTest {

    private static final Long SESSION_ID = 100L;
    private static final Long RUN_ID = 200L;
    private static final String CALL_ID = "call-wait-1";

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
    PendingWaitSpi pendingWaitSpi;
    @Mock
    ToolResultReadService toolResultReadService;
    @Mock
    DelegateService delegateService;
    @Mock
    TaskMcpCredentialStore taskMcpCredentialStore;

    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolExecutor = new ToolExecutor(mcpSpis, builtinSpi, cliExecutorService, sessionTodoSpi,
                eventPublisher, clarificationSpi, pendingWaitSpi, delegateService, toolResultReadService, taskMcpCredentialStore);
    }

    private static RuntimeTool waitTool() {
        return RuntimeTool.builder()
                .capabilityType("builtin")
                .capabilityId(10L)
                .llmToolName(WaitConstants.TOOL_NAME)
                .toolRef(ToolRefs.builtin(WaitConstants.INTERNAL_NAME))
                .description(WaitConstants.DESCRIPTION)
                .execBinding(Map.of(ExecBindingKeys.BUILTIN_INTERNAL_NAME, WaitConstants.INTERNAL_NAME))
                .build();
    }

    @Test
    void wait_returnsWaitingImmediately_withoutSleeping() {
        String result = toolExecutor.execute(
                new TurnToolCall(CALL_ID, WaitConstants.TOOL_NAME, "{\"ms\":90000}"),
                SESSION_ID, RUN_ID, List.of(waitTool()));

        assertTrue(result.contains("\"status\":\"waiting\""),
                "应立即返回 waiting，让 SessionRunner 判定后 break，而不是等满 90s");
        verify(pendingWaitSpi).createPending(SESSION_ID, RUN_ID, CALL_ID, 90000);
    }

    @Test
    void wait_neverFallsThroughToBuiltinSpi() {
        toolExecutor.execute(
                new TurnToolCall(CALL_ID, WaitConstants.TOOL_NAME, "{\"ms\":1000}"),
                SESSION_ID, RUN_ID, List.of(waitTool()));

        // wait has no SPI bean; if interception fails it lands here and reports "Unknown tool"
        verify(builtinSpi, never()).executeBuiltinTool(any(), any(), any(), any(), any());
    }

    @Test
    void wait_recordsCallIdSoWakeCanRewriteItsToolResult() {
        given(pendingWaitSpi.createPending(any(), any(), any(), any(Integer.class))).willReturn(1L);

        toolExecutor.execute(
                new TurnToolCall("call-xyz", WaitConstants.TOOL_NAME, "{\"ms\":1000}"),
                SESSION_ID, RUN_ID, List.of(waitTool()));

        // callId is the only key used at wake time by getByRunAndCallId to locate the tool call record
        verify(pendingWaitSpi).createPending(SESSION_ID, RUN_ID, "call-xyz", 1000);
    }

    @Test
    void malformedArgs_failWithoutCreatingAPendingRecord() {
        String result = toolExecutor.execute(
                new TurnToolCall(CALL_ID, WaitConstants.TOOL_NAME, "not-json"),
                SESSION_ID, RUN_ID, List.of(waitTool()));

        // Same as clarification: if arguments cannot be parsed, fail explicitly so the model retries instead of guessing a duration to suspend for.
        assertTrue(result.contains("Failed to process wait"), "实际: " + result);
        // Critical invariant: a failed wait must never leave a pending record — otherwise nothing will ever claim it,
        // and the run stays in WAITING until cron falls back, after which cron wakes with no matching tool call result.
        verify(pendingWaitSpi, never()).createPending(any(), any(), any(), any(Integer.class));
    }

    @Test
    void otherBuiltinToolsAreUnaffected() {
        RuntimeTool other = RuntimeTool.builder()
                .capabilityType("builtin")
                .llmToolName("builtin_6")
                .toolRef(ToolRefs.builtin("docwrite"))
                .execBinding(Map.of(ExecBindingKeys.BUILTIN_INTERNAL_NAME, "docwrite"))
                .build();
        given(builtinSpi.executeBuiltinTool(any(), any(), any(), any(), any())).willReturn("{\"ok\":true}");

        String result = toolExecutor.execute(
                new TurnToolCall("c2", "builtin_6", "{}"), SESSION_ID, RUN_ID, List.of(other));

        assertEquals("{\"ok\":true}", result);
        verify(pendingWaitSpi, never()).createPending(any(), any(), any(), any(Integer.class));
    }
}