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
 * wait 工具的挂起-回归约。
 *
 * <p>核心不变量只有一条，但极容易被破坏：<b>wait 绝不能阻塞</b>。
 * 一旦在这里 sleep，它就会占住 maxParallel 信号量并撞上 tool.execution-timeout，
 * 「等超过 60s」这个需求会重新变得不可满足 —— 也就是这次改造的出发点。
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

        // wait 没有 SPI bean；若拦截失效就会落到这里报「Unknown tool」
        verify(builtinSpi, never()).executeBuiltinTool(any(), any(), any(), any());
    }

    @Test
    void wait_recordsCallIdSoWakeCanRewriteItsToolResult() {
        given(pendingWaitSpi.createPending(any(), any(), any(), any(Integer.class))).willReturn(1L);

        toolExecutor.execute(
                new TurnToolCall("call-xyz", WaitConstants.TOOL_NAME, "{\"ms\":1000}"),
                SESSION_ID, RUN_ID, List.of(waitTool()));

        // callId 是唤醒时用 getByRunAndCallId 定位 tool call 记录的唯一凭据
        verify(pendingWaitSpi).createPending(SESSION_ID, RUN_ID, "call-xyz", 1000);
    }

    @Test
    void malformedArgs_failWithoutCreatingAPendingRecord() {
        String result = toolExecutor.execute(
                new TurnToolCall(CALL_ID, WaitConstants.TOOL_NAME, "not-json"),
                SESSION_ID, RUN_ID, List.of(waitTool()));

        // 与澄清同款：参数不可解析就明确报错让模型重试，而不是猜一个时长去挂起。
        assertTrue(result.contains("Failed to process wait"), "实际: " + result);
        // 关键不变量：失败的 wait 绝不能留下 pending 记录 —— 否则没有任何东西会去认领它，
        // run 会停在 WAITING 直到 cron 兜底，而 cron 醒来后又没有对应的 tool call 结果。
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
        given(builtinSpi.executeBuiltinTool(any(), any(), any(), any())).willReturn("{\"ok\":true}");

        String result = toolExecutor.execute(
                new TurnToolCall("c2", "builtin_6", "{}"), SESSION_ID, RUN_ID, List.of(other));

        assertEquals("{\"ok\":true}", result);
        verify(pendingWaitSpi, never()).createPending(any(), any(), any(), any(Integer.class));
    }
}