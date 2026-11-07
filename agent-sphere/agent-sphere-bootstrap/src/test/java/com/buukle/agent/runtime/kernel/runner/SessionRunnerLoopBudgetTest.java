package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the trailing loop budget preview.
 *
 * <p>The preview must report "current loop / remaining loops" every turn, but it <b>must be rebuilt each turn rather than replaced in place</b>:
 * tool turns append assistant(tool_calls)/tool/observation messages to the tail, pushing the previous copy into
 * the middle of the history — wasting context and producing two contradictory "turns remaining". Forced finalization (no more tool calls)
 * must <b>only</b> appear on the last round: banning tools early cuts unfinished work into a half-product.
 */
@ExtendWith(MockitoExtension.class)
class SessionRunnerLoopBudgetTest {

    private static final int MAX_LOOP = 128;

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
    ToolExecutor toolExecutor;
    @Mock
    TitleService titleService;
    @Mock
    AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    ChatAttachmentResolver attachmentResolver;

    /** Use the real builder: the budget text and marker recognition are themselves under test; mocking them tests nothing. */
    private final RunPromptBuilder runPromptBuilder = new RunPromptBuilder();

    SessionRunner runner;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
        org.mockito.Mockito.lenient().when(compactionService.resolveNominalChatMaxOutput(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(16_000);
        org.mockito.Mockito.lenient().when(compactionService.resolveRequestMaxOutput(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(16_000);
        runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, fallbackRouteExecutor, historyLoader,
                compactionService, inputManager, runPromptBuilder, toolExecutor, titleService,
                toolCallRecordSpi, redissonClient, attachmentResolver,
                new LlmRequestConfigurer(mock(SystemConfigSpi.class)), mock(ToolApprovalGrantSpi.class), mock(ToolApprovalService.class),
                new com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver(new com.buukle.agent.common.config.AgentRuntimeProperties()));
    }

    private void applyLoopBudget(List<ChatMessageDTO> messages, int loopCount, int maxLoopCount) throws Exception {
        Method m = SessionRunner.class.getDeclaredMethod(
                "applyLoopBudget", List.class, int.class, int.class);
        m.setAccessible(true);
        m.invoke(runner, messages, loopCount, maxLoopCount);
    }

    private void applyTodolistSuffix(List<ChatMessageDTO> messages, String todolist) throws Exception {
        Method m = SessionRunner.class.getDeclaredMethod("applyTodolistSuffix", List.class, String.class);
        m.setAccessible(true);
        m.invoke(runner, messages, todolist);
    }

    private static List<ChatMessageDTO> baseMessages() {
        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_SYSTEM).setContent("sys"));
        messages.add(new ChatMessageDTO().setRole(LlmApiConstant.ROLE_USER).setContent("hi"));
        return messages;
    }

    private static long budgetCount(List<ChatMessageDTO> messages) {
        return messages.stream()
                .filter(m -> m.getContent() instanceof String s
                        && s.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX))
                .count();
    }

    private static String tailText(List<ChatMessageDTO> messages) {
        return String.valueOf(messages.get(messages.size() - 1).getContent());
    }

    @Test
    void firstLoop_reportsLoopOneAndFullRemaining() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();

        applyLoopBudget(messages, 0, MAX_LOOP);

        String budget = tailText(messages);
        assertTrue(budget.contains("Loop 1/" + MAX_LOOP), "首轮应报 Loop 1/" + MAX_LOOP);
        assertTrue(budget.contains(MAX_LOOP + " turn(s) remaining"), "首轮剩余应为满额");
        assertFalse(budget.contains("converge"), "轮数充裕时不该催促");
    }

    @Test
    void nearLimit_loop_reportsRemainingAndUrgesToConverge() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();
        int loopCount = MAX_LOOP - RunnerConstants.LOOP_BUDGET_LOW_THRESHOLD;

        applyLoopBudget(messages, loopCount, MAX_LOOP);

        String budget = tailText(messages);
        assertTrue(budget.contains("Loop " + (loopCount + 1) + "/" + MAX_LOOP));
        assertTrue(budget.contains(RunnerConstants.LOOP_BUDGET_LOW_THRESHOLD + " turn(s) remaining"));
        assertTrue(budget.contains("converge"), "逼近上限应催促收口");
    }

    @Test
    void lastLoop_appendsHardInstructionAfterBudget() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();

        applyLoopBudget(messages, MAX_LOOP - 1, MAX_LOOP);

        assertEquals(2, messages.size() - 2, "末轮应只新增两条尾部 system 消息");
        assertTrue(tailText(messages).equals(RunnerConstants.FINAL_TURN_INSTRUCTION),
                "硬收口必须是最后一条（禁止再调工具的指令要压过预算文案）");
        String budget = String.valueOf(messages.get(messages.size() - 2).getContent());
        assertTrue(budget.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX), "预算段在硬收口之前");
        assertTrue(budget.contains("1 turn(s) remaining"), "末轮 remaining 应为 1 而非 0");
    }

    @Test
    void hardInstruction_startsAtN3_soModelHasRoomToFinish() throws Exception {
        // Finalization is only a hint, not enforced, and models often finish exactly on the last round (production runs 1575/1569 did).
        // Giving only 1 wrapping-up round is too tight: either the answer cannot be finished, or "interrupted" becomes the delivery excuse.
        int lead = RunnerConstants.FINAL_TURN_INSTRUCTION_LEAD;
        assertEquals(3, lead, "提前量按约定取 3 轮");

        // n-4: still outside the window
        List<ChatMessageDTO> before = baseMessages();
        applyLoopBudget(before, MAX_LOOP - lead - 1, MAX_LOOP);
        assertFalse(tailText(before).equals(RunnerConstants.FINAL_TURN_INSTRUCTION),
                "remaining > LEAD 时不应注入硬收口");

        // n-3: enters the window, and every round from here carries it
        for (int offset = lead; offset >= 1; offset--) {
            List<ChatMessageDTO> messages = baseMessages();
            applyLoopBudget(messages, MAX_LOOP - offset, MAX_LOOP);
            assertTrue(tailText(messages).equals(RunnerConstants.FINAL_TURN_INSTRUCTION),
                    "remaining=" + offset + " 时应持续注入硬收口");
        }
    }

    @Test
    void hardInstruction_wordingDoesNotClaimFinalTurn() throws Exception {
        // Saying "this is your last round" after moving to n-3 is false; the model may think it has one more chance and keep calling tools
        assertFalse(RunnerConstants.FINAL_TURN_INSTRUCTION.contains("final turn"),
                "硬收口文案不得声称「最后一轮」——它提前了 LEAD 轮注入");
        assertTrue(RunnerConstants.FINAL_TURN_INSTRUCTION.contains("must NOT call any more"),
                "仍需保留禁止调工具的硬指令");
    }

    @Test
    void outsideWindow_tailIsBudgetOnly() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();

        // remaining = LEAD + 1, just outside the window
        applyLoopBudget(messages, MAX_LOOP - RunnerConstants.FINAL_TURN_INSTRUCTION_LEAD - 1, MAX_LOOP);

        assertTrue(tailText(messages).startsWith(RunnerConstants.LOOP_BUDGET_PREFIX),
                "窗口外最后一条应是预算段而非硬收口");
    }

    @Test
    void rebuildsBudgetEveryLoop_insteadOfAccumulating() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();

        // Run 5 rounds, with assistant(tool_calls) + tool results in between — that is how real tool turns grow
        for (int loop = 0; loop < 5; loop++) {
            applyLoopBudget(messages, loop, MAX_LOOP);
            assertEquals(1, budgetCount(messages), "第 " + (loop + 1) + " 轮后预算段应恒为 1 份");
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT).setContent("calling"));
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_TOOL).setToolCallId("t" + loop).setContent("{}"));
        }

        // The last one must be the current round's (5/128); stale copies must not linger in the middle of history
        String currentBudget = messages.stream()
                .map(m -> String.valueOf(m.getContent()))
                .filter(s -> s.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX))
                .findFirst().orElseThrow();
        assertTrue(currentBudget.contains("Loop 5/" + MAX_LOOP),
                "幸存的预算段应是当前轮的文案，而不是第一轮的旧副本");
    }

    @Test
    void removesStaleHardInstructionFromEarlierLoop() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();
        messages.add(new ChatMessageDTO()
                .setRole(LlmApiConstant.ROLE_SYSTEM).setContent(RunnerConstants.FINAL_TURN_INSTRUCTION));

        applyLoopBudget(messages, 3, MAX_LOOP);

        assertEquals(0, messages.stream()
                        .filter(m -> RunnerConstants.FINAL_TURN_INSTRUCTION.equals(m.getContent()))
                        .count(),
                "上一轮残留的硬收口必须清掉——否则后续轮次会被永久禁止调工具");
    }

    @Test
    void todolistRefresh_insertsBeforeBudgetBlockSoNoticeStaysLast() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();
        applyLoopBudget(messages, 3, MAX_LOOP);

        applyTodolistSuffix(messages, "1. [done] step one");

        assertEquals(RunnerConstants.LOOP_BUDGET_PREFIX, String.valueOf(
                        messages.get(messages.size() - 1).getContent()).substring(0, RunnerConstants.LOOP_BUDGET_PREFIX.length()),
                "轮中刷新待办后预算段必须仍在最末，否则失去「最末可见 system」的效力");
        int todolistIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (runPromptBuilder.isTodolistSlot(String.valueOf(messages.get(i).getContent()))) {
                todolistIdx = i;
            }
        }
        assertTrue(todolistIdx >= 0 && todolistIdx < messages.size() - 1,
                "待办槽应插在预算段之前");
    }

    @Test
    void emptyMessages_isNoOp() throws Exception {
        List<ChatMessageDTO> messages = new ArrayList<>();

        applyLoopBudget(messages, 0, MAX_LOOP);

        assertTrue(messages.isEmpty(), "空列表不应凭空插入尾部消息");
    }

    // ---------- Retry backoff ----------

    @Test
    void llmTurnRetryBackoff_growsExponentiallyAndCaps() {
        // A fixed 1s backoff hits the model side's burst window and fires again in the same second, amplifying a blip into consecutive failures
        assertEquals(0L, SessionRunner.llmTurnRetryBackoffMs(0));
        assertEquals(RunnerConstants.LLM_TURN_RETRY_BASE_MS, SessionRunner.llmTurnRetryBackoffMs(1));
        assertEquals(RunnerConstants.LLM_TURN_RETRY_BASE_MS * 2, SessionRunner.llmTurnRetryBackoffMs(2));
        assertEquals(RunnerConstants.LLM_TURN_RETRY_BASE_MS * 4, SessionRunner.llmTurnRetryBackoffMs(3));

        long capped = SessionRunner.llmTurnRetryBackoffMs(30);
        assertEquals(RunnerConstants.LLM_TURN_RETRY_MAX_MS, capped, "必须封顶，否则重试链会拖垮单轮");
        // No further growth after the cap
        assertEquals(capped, SessionRunner.llmTurnRetryBackoffMs(50));
    }

    @Test
    void llmTurnRetryBudget_survivesThreeConsecutiveFailures() {
        // Production observation: a run hit 3 failures within 9 seconds, exhausting the old 2-attempt budget and going FAILED,
        // and only one that happened to hit exactly twice survived. A total of 4 attempts is required.
        int attempts = new AgentRuntimeProperties.RunnerConfig().getLlmTurnMaxRetries() + 1;
        assertEquals(4, attempts, "默认重试预算应为 3 次重试（合计 4 次尝试）");
    }
}
