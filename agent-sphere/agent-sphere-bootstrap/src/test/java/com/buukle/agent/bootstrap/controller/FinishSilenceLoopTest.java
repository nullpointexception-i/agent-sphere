package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.model.dtvo.complete.FinishReason;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RMapCache;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1/L2 end-to-end: an agentic run can only finalize through `finish`; silence is an anomaly.
 *
 * <p>The core user-reported case: a small model writes a long paragraph and stops without calling a single tool; the old loop treated "no tool call"
 * as "done", so the task failed silently. The criterion is now three-way:
 * <ul>
 *   <li>This turn contains finish → complete (with the declared summary)</li>
 *   <li>Other tool calls → continue</li>
 *   <li>No tool call at all (and tools have been used) → nudge; consecutive overruns become FAILED</li>
 * </ul>
 * Pure chat (tools never used) keeps the old semantics: returning text means done.
 */
@ExtendWith(MockitoExtension.class)
class FinishSilenceLoopTest {

    private static final long SESSION_ID = 93L;
    private static final long RUN_ID = 94L;

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
    RedissonClient redis;
    @Mock
    ChatAttachmentResolver attachmentResolver;
    @Mock
    com.buukle.agent.instance.spi.PendingWaitSpi pendingWaitSpi;

    private RunVO run;
    private KernelContext context;
    private RMapCache<Long, KernelContext> contextCache;

    /** Set up RunVO + Redisson context + routes and return an executable SessionRunner. */
    private SessionRunner newRunner(int maxLoopCount, String userMessage) {
        AgentRuntimeProperties.RunnerConfig runnerConfig = new AgentRuntimeProperties.RunnerConfig();
        runnerConfig.setMaxLoopCount(maxLoopCount);
        // No inline sleep in tests: the first transient error suspends immediately, which keeps assertions stable
        runnerConfig.setLlmRetryInlineRungs(0);
        runnerConfig.setLlmRetryLadder(List.of(java.time.Duration.ofMillis(10)));
        lenient().when(properties.getRunner()).thenReturn(runnerConfig);
        lenient().when(properties.getTool()).thenReturn(new AgentRuntimeProperties.ToolConfig());

        run = new RunVO();
        run.setId(RUN_ID);
        run.setSessionId(SESSION_ID);
        run.setStatus("RUNNING");
        lenient().when(runSpi.getRun(RUN_ID)).thenReturn(run);
        // Conditional status updates are real DB behavior: a mock default of false is misread as "run already terminal" and exits immediately
        lenient().when(runSpi.markRunning(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
        lenient().when(runSpi.markWaitingForRetry(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        lenient().when(runSpi.resetRetryState(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);

        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setId(1L);
        route.setCompany("openai");
        route.setBaseUrl("http://fixture.local");
        route.setModelName("fixture-model");
        lenient().when(routeListBuilder.fromContext(any())).thenReturn(List.of(route));

        KernelContext ctx = KernelContext.builder()
                .modelRoute(route)
                .agentInstance(new InstanceVO())
                .tools(List.of())
                .build();
        context = ctx;
        @SuppressWarnings("unchecked")
        RMapCache<Long, KernelContext> cache = mock(RMapCache.class);
        contextCache = cache;
        lenient().when(redis.<Long, KernelContext>getMapCache(DistributedRuntimeConstants.KEY_CTX)).thenReturn(cache);
        lenient().when(cache.get(SESSION_ID)).thenReturn(ctx);
        @SuppressWarnings("unchecked")
        RSet<Object> cancel = mock(RSet.class);
        lenient().when(redis.<Object>getSet(anyString())).thenReturn(cancel);
        @SuppressWarnings("unchecked")
        RBucket<Long> pending = mock(RBucket.class);
        lenient().when(redis.<Long>getBucket(DistributedRuntimeConstants.sessionPendingRunKey(SESSION_ID)))
                .thenReturn(pending);
        lenient().when(pending.getAndDelete()).thenReturn(RUN_ID);
        lenient().when(inputManager.promoteInput(SESSION_ID)).thenReturn(
                new SessionInputManager.InputMessage(userMessage, null, 0L, false, List.of()), null);
        org.mockito.Mockito.lenient().when(compactionService.resolveNominalChatMaxOutput(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(16_000);
        org.mockito.Mockito.lenient().when(compactionService.resolveRequestMaxOutput(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(16_000);

        SessionRunner runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, new FallbackRouteExecutor(), historyLoader,
                compactionService, inputManager, new RunPromptBuilder(), toolExecutor, titleService,
                toolCallRecordSpi, redis, attachmentResolver,
                new LlmRequestConfigurer(mock(SystemConfigSpi.class)), mock(ToolApprovalGrantSpi.class), mock(ToolApprovalService.class),
                new com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver(new com.buukle.agent.common.config.AgentRuntimeProperties()));
        try {
            java.lang.reflect.Field field = SessionRunner.class.getDeclaredField("pendingWaitSpi");
            field.setAccessible(true);
            field.set(runner, pendingWaitSpi);
        } catch (Exception e) {
            throw new IllegalStateException("inject pendingWaitSpi failed", e);
        }
        return runner;
    }
    /** Script model output per turn without appending Finish (to simulate a truncated stream). */
    private void scriptModelWithoutFinish(java.util.function.IntFunction<LLMEvent[]> script) {
        AtomicInteger turns = new AtomicInteger();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            for (LLMEvent e : script.apply(turns.getAndIncrement())) {
                onEvent.accept(e);
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        }).when(kernelLlmService).stream(anyString(), anyString(), any(), anyString(), any(), any(), any());
    }

    /** Script model output per turn; turn starts at 0. */
    private void scriptModel(java.util.function.IntFunction<LLMEvent[]> script) {
        AtomicInteger turns = new AtomicInteger();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            for (LLMEvent e : script.apply(turns.getAndIncrement())) {
                onEvent.accept(e);
            }
            // A normal stream end must carry finish; a missing Finish is now treated as truncation.
            onEvent.accept(new LLMEvent.Finish(FinishReason.STOP, null));
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        }).when(kernelLlmService).stream(anyString(), anyString(), any(), anyString(), any(), any(), any());
    }

    private static LLMEvent tool(String name) {
        return new LLMEvent.ToolCall("call-" + name, name, "{}");
    }

    private static LLMEvent text(String t) {
        return new LLMEvent.TextDelta(t);
    }

    @Test
    void silentTurnsAfterToolUse_areNotCompletion_andFailAfterRetries() {
        SessionRunner runner = newRunner(25, "objective");
        scriptModel(turn -> turn == 0 ? new LLMEvent[]{tool("builtin_2")} : new LLMEvent[]{text("bye")});

        runner.run(SESSION_ID);

        assertEquals("FAILED", run.getStatus(),
                "agentic 流程里连续静默必须判失败 —— 旧实现会把它当成正常完成");
        ArgumentCaptor<RuntimeEventVO> events = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        boolean reportsRealCount = events.getAllValues().stream()
                .filter(e -> RunStatus.FAILED.equals(e.getEventType()))
                .map(e -> e.getData().getErrorMessage())
                .anyMatch(msg -> msg != null && msg.contains("3"));
        org.junit.jupiter.api.Assertions.assertTrue(reportsRealCount,
                "错误原因必须记录实际的第三次静默，而不是旧计数 2");
    }

    @Test
    void streamTimeout_suspendsRunForRetryInsteadOfFailing() {
        SessionRunner runner = newRunner(25, "objective");
        doAnswer(inv -> {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("LLM stream timed out after 120s"));
            return failed;
        }).when(kernelLlmService).stream(anyString(), anyString(), any(), anyString(), any(), any(), any());

        runner.run(SESSION_ID);

        assertEquals("WAITING", run.getStatus(), "瞬时错误应挂起重试，而不是直接失败");
        verify(runSpi).markWaitingForRetry(RUN_ID, 1);
        verify(pendingWaitSpi).createRetry(eq(SESSION_ID), eq(RUN_ID), eq(1), any(),
                eq("connect_timeout"), isNull(), contains("timed out"));
        assertTrue(publishedWaitingRetry(), "挂起事件必须带重试进度，前端才能显示倒计时");
    }

    private boolean publishedWaitingRetry() {
        ArgumentCaptor<RuntimeEventVO> events = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        return events.getAllValues().stream()
                .filter(e -> RunStatus.WAITING.equals(e.getEventType()))
                .map(RuntimeEventVO::getData)
                .anyMatch(d -> d != null && "LLM_RETRY".equals(d.getWaitKind())
                        && d.getRetryAttempt() != null && d.getNextRetryAt() != null);
    }

    @Test
    void streamEndingWithoutFinish_isRejectedAsTruncated() {
        SessionRunner runner = newRunner(25, "objective");
        scriptModelWithoutFinish(turn -> new LLMEvent[]{text("半截答案")});

        runner.run(SESSION_ID);

        assertEquals("FAILED", run.getStatus(), "没有 Finish 的流必须判为异常");
        assertTrue(failedMessageContains("without finish_reason"),
                "run 失败原因必须说明缺失 Finish");
    }

    private boolean failedMessageContains(String needle) {
        ArgumentCaptor<RuntimeEventVO> events = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        return events.getAllValues().stream()
                .filter(e -> RunStatus.FAILED.equals(e.getEventType()))
                .map(e -> e.getData().getErrorMessage())
                .anyMatch(msg -> msg != null && msg.contains(needle));
    }

    @Test
    void finishTool_completesRunWithSummary_andIsNotLoopCapped() {
        // maxLoopCount=2: round 2 (loopCount=1) is the "last round"; verify finish is not treated as a loop cap
        SessionRunner runner = newRunner(2, "objective");
        scriptModel(turn -> turn == 0
                ? new LLMEvent[]{tool("builtin_2")}
                : new LLMEvent[]{tool(FinishConstants.TOOL_NAME)});

        runner.run(SESSION_ID);

        assertEquals("COMPLETED", run.getStatus());
        assertNotEquals(Boolean.TRUE, run.getLoopCapped(),
                "最后一轮调 finish 是干净完成，不能被标 loopCapped（否则任务守卫会判 FAILED）");
    }

    @Test
    void pureChatWithoutAnyToolStillCompletesOnPlainText() {
        SessionRunner runner = newRunner(25, "2+2?");
        scriptModel(turn -> new LLMEvent[]{text("4")});

        runner.run(SESSION_ID);

        assertEquals("COMPLETED", run.getStatus(), "从未用过工具的纯聊天，回文本就该算完成");
        assertEquals("4", run.getAssistantReply());
        verify(runSpi).resetRetryState(RUN_ID);
        verify(inputManager).clear(SESSION_ID);
    }

    @Test
    void implicitTextProtocol_completesNonEmptyStopTextAfterToolUse() {
        SessionRunner runner = newRunner(25, "objective");
        run.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        scriptModel(turn -> turn == 0 ? new LLMEvent[]{tool("builtin_2")} : new LLMEvent[]{text("done")});

        runner.run(SESSION_ID);

        assertEquals("COMPLETED", run.getStatus());
        assertEquals("done", run.getAssistantReply());
    }

    @Test
    void contextCacheExpiryAfterToolUse_failsWithContextLost_andRefreshesTtl() {
        SessionRunner runner = newRunner(25, "objective");
        // topCtx reads and the first loop iteration get the context; afterwards simulate Redis TTL expiry returning null.
        when(contextCache.get(SESSION_ID)).thenReturn(context, context, (KernelContext) null);
        scriptModel(turn -> turn == 0 ? new LLMEvent[]{tool("builtin_2")} : new LLMEvent[]{text("bye")});

        runner.run(SESSION_ID);

        assertEquals("FAILED", run.getStatus(),
                "context 过期后不能继续跑空工具集，否则会被误判为连续静默");
        assertTrue(failedMessageContains("context"),
                "Failure reason must state that the run context was lost, not that the model did not call a tool");
        verify(contextCache, atLeastOnce()).put(
                eq(SESSION_ID), eq(context), anyLong(), eq(TimeUnit.MILLISECONDS));
    }
}
