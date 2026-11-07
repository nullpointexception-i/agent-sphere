package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.runtime.kernel.runner.support.SessionInputManager;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.runner.compaction.CompactionTracker;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.contract.TurnResult;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests for the pieces extracted from {@code SessionRunner#runInternal}.
 */
@ExtendWith(MockitoExtension.class)
class SessionRunnerRunLoopTest {

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
    FallbackRouteExecutor fallbackRouteExecutor;
    @Mock
    HistoryLoader historyLoader;
    @Mock
    CompactionService compactionService;
    @Mock
    SessionInputManager inputManager;
    @Mock
    RunPromptBuilder runPromptBuilder;
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

    SessionRunner runner;
    RSet<Object> cancelSet;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(properties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
        cancelSet = mock(RSet.class);
        lenient().when(cancelSet.contains(any())).thenReturn(false);
        lenient().when(redissonClient.getSet(anyString())).thenReturn(cancelSet);
        runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, fallbackRouteExecutor, historyLoader,
                compactionService, inputManager, runPromptBuilder, toolExecutor, titleService,
                toolCallRecordSpi, redissonClient, attachmentResolver,
                new LlmRequestConfigurer(mock(SystemConfigSpi.class)),
                mock(ToolApprovalGrantSpi.class), mock(ToolApprovalService.class),
                new ReasoningEffortResolver(new AgentRuntimeProperties()));
    }

    // ---------- CompactionTracker ----------

    @Test
    void compactionTracker_failureDisablesAfterLimit() {
        CompactionTracker tracker = new CompactionTracker(0);
        for (int i = 0; i < com.buukle.agent.runtime.kernel.constants.RunnerConstants
                .MAX_CONSECUTIVE_COMPACTION_FAILURES; i++) {
            assertFalse(tracker.onCompacted(CompactionOutcome.NO_PROGRESS, 1L));
        }
        assertTrue(tracker.disabled());
        assertTrue(tracker.skip());
        assertEquals(com.buukle.agent.runtime.kernel.constants.RunnerConstants
                .MAX_CONSECUTIVE_COMPACTION_FAILURES, tracker.consecutiveFailures());
    }

    @Test
    void compactionTracker_successResetsConsecutiveFailures() {
        CompactionTracker tracker = new CompactionTracker(0);
        tracker.onCompacted(CompactionOutcome.FAILED, 1L);
        assertTrue(tracker.onCompacted(CompactionOutcome.COMPLETED, 1L));
        assertEquals(0, tracker.consecutiveFailures());
    }

    @Test
    void compactionTracker_runCompactionUpdatesBudget() {
        CompactionTracker tracker = new CompactionTracker(2);
        tracker.onRunCompacted(7, 1L, 12);
        assertEquals(1, tracker.runAttempts());
        assertEquals(3, tracker.runCount());
        assertEquals(7, tracker.lastRunLoop());
    }

    @Test
    void compactionTracker_toolLoopResetsRetriesAndKeepsDisabledSkip() {
        CompactionTracker tracker = new CompactionTracker(0);
        for (int i = 0; i < com.buukle.agent.runtime.kernel.constants.RunnerConstants
                .MAX_CONSECUTIVE_COMPACTION_FAILURES; i++) {
            tracker.onCompacted(CompactionOutcome.FAILED, 1L);
        }
        tracker.resetForToolLoop();
        assertEquals(0, tracker.retries());
        assertTrue(tracker.skip());
    }

    // ---------- handleAfterToolLoop ----------

    @Test
    void afterToolLoop_notLastLoopKeepsRunning() throws Exception {
        RunVO run = new RunVO();
        TurnResult turn = TurnResult.toolCalls(null, List.of(
                new com.buukle.agent.runtime.kernel.contract.TurnToolCall("c1", "tool", "{}")));

        boolean shouldBreak = invokeAfterToolLoop(run, turn, new ArrayList<>(), new StringBuilder(), false, 5);

        assertFalse(shouldBreak);
        verify(runSpi, never()).updateRun(any());
    }

    @Test
    void afterToolLoop_lastLoopCapsAndCompletes() throws Exception {
        RunVO run = new RunVO();
        TurnResult turn = TurnResult.toolCalls(null, List.of(
                new com.buukle.agent.runtime.kernel.contract.TurnToolCall("c1", "tool", "{}")));

        boolean shouldBreak = invokeAfterToolLoop(run, turn, new ArrayList<>(),
                new StringBuilder("partial"), true, 5);

        assertTrue(shouldBreak);
        assertTrue(run.getLoopCapped());
        assertEquals(RunStatus.COMPLETED.name(), run.getStatus());
        assertEquals("partial", run.getAssistantReply());
    }

    private boolean invokeAfterToolLoop(RunVO run, TurnResult turn, List<ChatMessageDTO> messages,
                                        StringBuilder allContent, boolean isLastLoop, int maxLoopCount)
            throws Exception {
        Method method = SessionRunner.class.getDeclaredMethod("handleAfterToolLoop",
                Long.class, Long.class, RunVO.class, TurnResult.class, List.class,
                StringBuilder.class, boolean.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(runner, 7L, 11L, run, turn, messages, allContent,
                isLastLoop, maxLoopCount);
    }

    // ---------- finalizeRun ----------

    @Test
    void finalizeRun_cancelledRunWritesCancelledTerminalState() throws Exception {
        RunVO run = new RunVO();
        run.setStatus(RunStatus.RUNNING.name());
        when(cancelSet.contains(any())).thenReturn(true);

        Method method = SessionRunner.class.getDeclaredMethod("finalizeRun",
                Long.class, Long.class, RunVO.class);
        method.setAccessible(true);
        method.invoke(runner, 7L, 11L, run);

        assertEquals(RunStatus.CANCELLED.name(), run.getStatus());
        verify(runSpi).updateRun(run);
    }

    @Test
    void finalizeRun_runningRunWithoutTerminalFails() throws Exception {
        RunVO run = new RunVO();
        run.setStatus(RunStatus.RUNNING.name());

        Method method = SessionRunner.class.getDeclaredMethod("finalizeRun",
                Long.class, Long.class, RunVO.class);
        method.setAccessible(true);
        method.invoke(runner, 7L, 11L, run);

        assertEquals(RunStatus.FAILED.name(), run.getStatus());
    }

    // ---------- handleFinishTurn ----------

    @Test
    void finishTurn_closesOtherCallsAndCompletesRun() throws Exception {
        RunVO run = new RunVO();
        TurnResult turn = TurnResult.toolCalls(null, List.of(
                new com.buukle.agent.runtime.kernel.contract.TurnToolCall("c1", "other_tool", "{}"),
                new com.buukle.agent.runtime.kernel.contract.TurnToolCall("c2",
                        com.buukle.agent.runtime.kernel.constants.FinishConstants.TOOL_NAME,
                        "{\"summary\":\"done\",\"status\":\"success\"}")));

        Method method = SessionRunner.class.getDeclaredMethod("handleFinishTurn",
                Long.class, Long.class, RunVO.class, TurnResult.class, List.class, StringBuilder.class);
        method.setAccessible(true);
        boolean handled = (boolean) method.invoke(runner, 7L, 11L, run, turn,
                List.of(), new StringBuilder("streamed"));

        assertTrue(handled);
        assertEquals(RunStatus.COMPLETED.name(), run.getStatus());
        assertEquals("done", run.getAssistantReply());
        assertEquals("success", run.getFinishStatus());
        verify(runSpi).updateRun(run);
        verify(eventPublisher, org.mockito.Mockito.atLeast(3)).publishEvent(any(Object.class));
    }

    // ---------- prepareFirstTurnMessages ----------

    @Test
    void prepareFirstTurn_assemblesSystemPromptHistoryAndUser() throws Exception {
        KernelContext ctx = KernelContext.builder().build();
        when(runPromptBuilder.buildSystemPrompt(ctx)).thenReturn("SYS");
        ChatMessageDTO historyUser = new ChatMessageDTO()
                .setRole(com.buukle.agent.common.constant.LlmRoles.USER)
                .setContent("old");
        when(historyLoader.loadResult(9L, 4L, false))
                .thenReturn(new HistoryLoader.HistoryResult(List.of(historyUser), 0));
        when(toolCallRecordSpi.getLatestBySessionAndToolName(anyLong(), anyString()))
                .thenReturn(null);

        List<ChatMessageDTO> messages = new ArrayList<>();
        SessionInputManager.InputMessage input =
                new SessionInputManager.InputMessage("hello", null, 0L, false, List.of());

        int start = invokePrepare(messages, 9L, 4L, ctx, input, -1);

        assertEquals(3, messages.size());
        assertEquals("SYS", messages.get(0).getContent());
        assertEquals("old", messages.get(1).getContent());
        assertEquals("hello", messages.get(2).getContent());
        // System prompt is index 0, history starts at 1, so the current run starts at 1.
        assertEquals(1, start);
        verify(titleService).generateIfNeeded(ctx, "hello", 9L, 4L);
    }

    private int invokePrepare(List<ChatMessageDTO> messages, Long sessionId, Long runId,
                              KernelContext ctx, SessionInputManager.InputMessage input,
                              int currentRunStart) throws Exception {
        Method method = SessionRunner.class.getDeclaredMethod("prepareFirstTurnMessages",
                Long.class, Long.class, KernelContext.class,
                SessionInputManager.InputMessage.class, List.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(runner, sessionId, runId, ctx, input, messages, currentRunStart);
    }

    // ---------- handleCompleteTurn ----------

    @Test
    void completeTurn_normalCompletionSetsReplyAndStatus() throws Exception {
        RunVO run = new RunVO();
        run.setCompletionProtocol(CompletionProtocol.STRICT_FINISH.name());
        TurnResult turn = TurnResult.complete("final answer");
        StringBuilder allContent = new StringBuilder("final answer");

        Object result = invokeComplete(run, turn, allContent, false, 0, 2);

        assertEquals("BREAK", actionOf(result));
        assertEquals(RunStatus.COMPLETED.name(), run.getStatus());
        assertEquals("final answer", run.getAssistantReply());
        verify(runSpi).updateRun(run);
    }

    @Test
    void completeTurn_silentTurnContinuesUntilLimit() throws Exception {
        RunVO run = new RunVO();
        TurnResult turn = TurnResult.complete("still thinking");

        Object result = invokeComplete(run, turn, new StringBuilder(), true, 0, 2);

        assertEquals("CONTINUE", actionOf(result));
        assertEquals(1, silentTurnsOf(result));
        verify(runSpi, never()).updateRun(any());
    }

    @Test
    void completeTurn_silentLimitFailsRun() throws Exception {
        RunVO run = new RunVO();
        TurnResult turn = TurnResult.complete("still thinking");

        Object result = invokeComplete(run, turn, new StringBuilder(), true, 2, 2);

        assertEquals("BREAK", actionOf(result));
        assertEquals(RunStatus.FAILED.name(), run.getStatus());
    }

    @Test
    void completeTurn_pendingInputContinuesWithInput() throws Exception {
        RunVO run = new RunVO();
        when(inputManager.hasPending(7L)).thenReturn(true);

        Object result = invokeComplete(run, TurnResult.complete("answer"), new StringBuilder(), false, 0, 2);

        assertEquals("CONTINUE_WITH_INPUT", actionOf(result));
    }

    @Test
    void completeTurn_cancellationWins() throws Exception {
        RunVO run = new RunVO();
        when(cancelSet.contains(any())).thenReturn(true);

        Object result = invokeComplete(run, TurnResult.complete("answer"), new StringBuilder(), false, 0, 2);

        assertEquals("BREAK", actionOf(result));
        verify(runSpi, never()).updateRun(any());
    }

    @Test
    void completeTurn_implicitTextCompletesAfterTools() throws Exception {
        RunVO run = new RunVO();
        run.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        TurnResult turn = TurnResult.complete("implicit answer");

        Object result = invokeComplete(run, turn, new StringBuilder("implicit answer"), true, 0, 2);

        assertEquals("BREAK", actionOf(result));
        assertEquals(RunStatus.COMPLETED.name(), run.getStatus());
    }

    private Object invokeComplete(RunVO run, TurnResult turn, StringBuilder allContent,
                                  boolean toolsEverExecuted, int silentTurns, int maxSilentTurns)
            throws Exception {
        Method method = SessionRunner.class.getDeclaredMethod("handleCompleteTurn",
                Long.class, Long.class, RunVO.class, TurnResult.class, StringBuilder.class,
                boolean.class, int.class, int.class);
        method.setAccessible(true);
        return method.invoke(runner, 7L, 11L, run, turn, allContent,
                toolsEverExecuted, silentTurns, maxSilentTurns);
    }

    private static String actionOf(Object completeResult) throws Exception {
        Method action = completeResult.getClass().getDeclaredMethod("action");
        action.setAccessible(true);
        return action.invoke(completeResult).toString();
    }

    private static int silentTurnsOf(Object completeResult) throws Exception {
        Method silent = completeResult.getClass().getDeclaredMethod("silentTurns");
        silent.setAccessible(true);
        return (int) silent.invoke(completeResult);
    }
}
