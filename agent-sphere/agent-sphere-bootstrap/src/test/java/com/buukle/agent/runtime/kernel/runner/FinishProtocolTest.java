package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.model.dtvo.complete.FinishReason;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.CompactionService.RunCompactionResult;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「沉默 ≠ 完成」的三层护栏（L0 截断信号 / L1 finish 工具 / L2 静默追问）。
 *
 * <p>L0：过去流结束一律上报 {@code finish_reason=stop}，于是被 max_tokens 截断（length）或
 * 被安全策略拦截（content_filter）的响应与「模型主动说完」无法区分，半截答案被标 COMPLETED。
 * 现在必须区分，且**没有工具调用**时不能再当完成。
 *
 * <p>L2：进入 agentic 流程（用过工具）之后，沉默只能靠 {@code finish} 收口；连续静默超限判失败。
 */
@ExtendWith(MockitoExtension.class)
class FinishProtocolTest {

    @Mock
    AgentRuntimeProperties properties;
    @Mock
    RunSpi runSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    KernelLlmService kernelLlmService;
    @Mock
    ApiKeySpi apiKeySpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    RouteListBuilder routeListBuilder;
    @Mock
    HistoryLoader historyLoader;
    @Mock
    CompactionService compactionService;
    @Mock
    SessionInputManager inputManager;
    @Mock
    ToolExecutor toolExecutor;
    @Mock
    TitleService titleService;
    @Mock
    AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    ChatAttachmentResolver attachmentResolver;

    private final RunPromptBuilder runPromptBuilder = new RunPromptBuilder();
    private SessionRunner runner;
    /** 本轮使用的路由：resolveRoutes 只在 ctx 带 modelRoute/fallbackRoutes 时才走 RouteListBuilder。 */
    private ModelRouteFullVO route;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
        // 取消检查走 Redisson RSet：不 stub 会 NPE（路由 lambda 第一件事就是查取消态）
        @SuppressWarnings("unchecked")
        RSet<Object> cancelSet = mock(RSet.class);
        lenient().when(cancelSet.contains(any())).thenReturn(false);
        lenient().when(redissonClient.getSet(anyString())).thenReturn(cancelSet);
        runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, new FallbackRouteExecutor(), historyLoader,
                compactionService, inputManager, runPromptBuilder, toolExecutor, titleService,
                toolCallRecordSpi, redissonClient, attachmentResolver,
                new LlmRequestConfigurer(mock(SystemConfigSpi.class)), mock(ToolApprovalGrantSpi.class), mock(ToolApprovalService.class));
    }

    /** 让本轮固定产出给定的流式事件序列。 */
    private void emit(LLMEvent... events) {
        route = new ModelRouteFullVO();
        route.setId(1L);
        route.setCompany("openai");
        route.setBaseUrl("http://fixture.local");
        route.setModelName("fixture-model");
        lenient().when(routeListBuilder.fromContext(any())).thenReturn(List.of(route));
        lenient().when(kernelLlmService.stream(anyString(), anyString(), any(), anyString(),
                any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            for (LLMEvent e : events) {
                onEvent.accept(e);
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        });
    }

    private TurnResult runTurn() throws Exception {
        return runTurn(false);
    }

    private TurnResult runTurn(boolean skipCompaction) throws Exception {
        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("do it"));
        Method m = SessionRunner.class.getDeclaredMethod(
                "runTurn", Long.class, Long.class, List.class, List.class, List.class,
                KernelContext.class, AtomicReference.class, boolean.class,
                int.class, int.class, int.class, int.class, int.class);
        m.setAccessible(true);
        return (TurnResult) m.invoke(runner, 1L, 2L, messages, List.of(), List.of(),
                KernelContext.builder().modelRoute(route).build(), new AtomicReference<>(), skipCompaction,
                0, 0, 0, -1000, 0);
    }

    // ---------- L0：截断 / 内容过滤不能当完成 ----------

    @Test
    void truncatedAnswerWithoutToolCalls_isAnErrorNotCompletion() throws Exception {
        emit(new LLMEvent.TextDelta("半截答案"), new LLMEvent.Finish(FinishReason.LENGTH, null));

        TurnResult result = runTurn();

        assertEquals(TurnOutcome.ERROR, result.outcome(), "被 max_tokens 截断不能当完成");
        assertTrue(result.errorMessage().contains("finish_reason=length"), result.errorMessage());
        assertEquals(FinishReason.LENGTH, result.finishReason());
    }

    @Test
    void contentFilteredAnswerWithoutToolCalls_isAnError() throws Exception {
        emit(new LLMEvent.TextDelta("partial"), new LLMEvent.Finish(FinishReason.CONTENT_FILTER, null));

        TurnResult result = runTurn();

        assertEquals(TurnOutcome.ERROR, result.outcome());
        assertTrue(result.errorMessage().contains("content_filter"), result.errorMessage());
    }

    @Test
    void truncatedButToolCallsPresent_isStillAToolTurn() throws Exception {
        // 工具参数已完整或本就不可用：正文截断不该阻断工具轮
        emit(new LLMEvent.TextDelta("partial"),
                new LLMEvent.ToolCall("call-1", "builtin_5", "{}"),
                new LLMEvent.Finish(FinishReason.LENGTH, null));

        TurnResult result = runTurn();

        assertEquals(TurnOutcome.TOOL_CALLS, result.outcome());
        assertEquals(1, result.toolCalls().size());
    }

    @Test
    void normalStop_withoutToolCalls_isCompletion() throws Exception {
        emit(new LLMEvent.TextDelta("done"), new LLMEvent.Finish(FinishReason.STOP, null));

        TurnResult result = runTurn();

        assertEquals(TurnOutcome.COMPLETE, result.outcome());
        assertEquals("done", result.content());
        assertEquals(FinishReason.STOP, result.finishReason());
    }

    // ---------- 压缩重试耗尽：跳过压缩直接发超上下文 ----------

    @Test
    void skipCompactionFlagBypassesCompactionGate() throws Exception {
        emit(new LLMEvent.TextDelta("done"), new LLMEvent.Finish(FinishReason.STOP, null));
        when(compactionService.evaluate(any(), any(), any(), any())).thenReturn(
                new CompactionService.CompactionDecision(true, 0, 0, 0, 95_000, 100_000, 100_000));
        when(compactionService.compact(any(), any(), any())).thenReturn(CompactionOutcome.NO_PROGRESS);

        TurnResult compacted = runTurn(false);
        assertEquals(TurnOutcome.COMPACTED, compacted.outcome(),
                "正常轮次命中阈值时必须触发压缩");
        assertEquals(CompactionOutcome.NO_PROGRESS, compacted.compactionOutcome(),
                "压缩的真实结果必须回传给 caller，不能把‘触发过’当成成功");
        assertEquals(TurnOutcome.COMPLETE, runTurn(true).outcome(),
                "压缩重试耗尽后必须绕过压缩，带超上下文直接发请求");
    }

    @Test
    void runLocalCompactionResultIsReturnedToCaller() throws Exception {
        emit(new LLMEvent.TextDelta("done"), new LLMEvent.Finish(FinishReason.STOP, null));
        when(compactionService.evaluate(any(), any(), any(), any())).thenReturn(
                new CompactionService.CompactionDecision(true, 0, 0, 0, 95_000, 100_000, 100_000));
        when(compactionService.compact(any(), any(), any())).thenReturn(CompactionOutcome.NO_HISTORY);
        List<ChatMessageDTO> rebuilt = List.of(
                new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("tail"));
        when(compactionService.compactRun(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new RunCompactionResult(CompactionOutcome.COMPLETED, rebuilt, 1, 9L, 0, 0));

        TurnResult result = runTurn(false);

        assertEquals(TurnOutcome.RUN_COMPACTED, result.outcome());
        assertEquals(rebuilt, result.rebuiltMessages());
        assertEquals(1, result.rebuiltCurrentRunStart());
    }

    // ---------- L2：静默追问消息的槽位语义 ----------

    @Test
    void silentTurnNudge_isReplacedInPlaceInsteadOfAccumulating() throws Exception {
        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("hi"));

        applyNudge(messages, 1);
        applyNudge(messages, 2);

        long nudgeCount = messages.stream()
                .filter(m -> m.getContent() instanceof String s
                        && s.startsWith(RunnerConstants.SILENT_TURN_NUDGE_PREFIX))
                .count();
        assertEquals(1, nudgeCount, "每轮重建必须替换而不是累积旧提示");

        applyNudge(messages, 0);
        assertEquals(0, messages.stream()
                        .filter(m -> m.getContent() instanceof String s
                                && s.startsWith(RunnerConstants.SILENT_TURN_NUDGE_PREFIX))
                        .count(),
                "静默计数归零后必须移除提示");
    }

    private void applyNudge(List<ChatMessageDTO> messages, int silentTurns) throws Exception {
        Method m = SessionRunner.class.getDeclaredMethod("applySilentTurnNudge", List.class, int.class);
        m.setAccessible(true);
        m.invoke(runner, messages, silentTurns);
    }

    // ---------- finish.summary 作为助手正文（界面空白的修复） ----------

    @Test
    void finishToolCall_emitsSummaryAsContentWhenNoContent() throws Exception {
        emit(new LLMEvent.ToolCall("finish-call", FinishConstants.TOOL_NAME,
                "{\"status\":\"success\",\"summary\":\"最终答案\"}"));

        runTurn();

        ArgumentCaptor<RuntimeEventVO> captor = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(e ->
                        e.getEventType() == FlowEventType.CONTENT_TOKEN
                                && "最终答案".equals(e.getData().getResponse())),
                "finish.summary 必须作为助手正文推出，否则前端只剩工具卡、答案空白");
    }

    @Test
    void finishToolCall_doesNotDuplicateSummaryWhenContentAlreadyStreamed() throws Exception {
        emit(new LLMEvent.TextDelta("正文"),
                new LLMEvent.ToolCall("finish-call", FinishConstants.TOOL_NAME,
                        "{\"status\":\"success\",\"summary\":\"最终答案\"}"));

        runTurn();

        ArgumentCaptor<RuntimeEventVO> captor = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        assertFalse(captor.getAllValues().stream().anyMatch(e ->
                        e.getEventType() == FlowEventType.CONTENT_TOKEN
                                && "最终答案".equals(e.getData().getResponse())),
                "正文已流式输出时不得重复发 summary");
    }
}
