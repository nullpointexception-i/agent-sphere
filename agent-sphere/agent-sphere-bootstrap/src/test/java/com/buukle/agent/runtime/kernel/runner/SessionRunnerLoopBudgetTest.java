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
 * 尾部 loop 预算预告的回归护栏。
 *
 * <p>预告每轮都要报「当前第几轮 / 还剩几轮」，但它<b>必须每轮重建而不是原位替换</b>：
 * 工具轮会往 messages 尾部追加 assistant(tool_calls)/tool/观察消息，上一轮的副本会被挤进
 * 历史中间——既白烧 context，又出现两份互相矛盾的「还剩几轮」。硬收口（禁止再调工具）
 * 则<b>只能</b>出现在最后一轮：提前禁工具会把没做完的活截成半成品。
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

    /** 用真实 builder：预算文案与 marker 识别本身就是被测对象，mock 掉等于没测。 */
    private final RunPromptBuilder runPromptBuilder = new RunPromptBuilder();

    SessionRunner runner;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
        runner = new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, fallbackRouteExecutor, historyLoader,
                compactionService, inputManager, runPromptBuilder, toolExecutor, titleService,
                toolCallRecordSpi, redissonClient, attachmentResolver,
                new LlmRequestConfigurer(mock(SystemConfigSpi.class)), mock(ToolApprovalGrantSpi.class), mock(ToolApprovalService.class));
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
        // 收口只是提示、不强制，而模型常常正好在最后一轮才收工（线上 run 1575/1569 都如此）。
        // 只给 1 轮收尾太紧：要么来不及写完整，要么只能把"被打断"当成交付理由。
        int lead = RunnerConstants.FINAL_TURN_INSTRUCTION_LEAD;
        assertEquals(3, lead, "提前量按约定取 3 轮");

        // n-4：还不在窗口内
        List<ChatMessageDTO> before = baseMessages();
        applyLoopBudget(before, MAX_LOOP - lead - 1, MAX_LOOP);
        assertFalse(tailText(before).equals(RunnerConstants.FINAL_TURN_INSTRUCTION),
                "remaining > LEAD 时不应注入硬收口");

        // n-3：进入窗口，且从这一轮起每轮都带
        for (int offset = lead; offset >= 1; offset--) {
            List<ChatMessageDTO> messages = baseMessages();
            applyLoopBudget(messages, MAX_LOOP - offset, MAX_LOOP);
            assertTrue(tailText(messages).equals(RunnerConstants.FINAL_TURN_INSTRUCTION),
                    "remaining=" + offset + " 时应持续注入硬收口");
        }
    }

    @Test
    void hardInstruction_wordingDoesNotClaimFinalTurn() throws Exception {
        // 提前到 n-3 后再说「这是你的最后一轮」是假的，模型可能据此以为还剩一次机会而继续调工具
        assertFalse(RunnerConstants.FINAL_TURN_INSTRUCTION.contains("final turn"),
                "硬收口文案不得声称「最后一轮」——它提前了 LEAD 轮注入");
        assertTrue(RunnerConstants.FINAL_TURN_INSTRUCTION.contains("must NOT call any more"),
                "仍需保留禁止调工具的硬指令");
    }

    @Test
    void outsideWindow_tailIsBudgetOnly() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();

        // remaining = LEAD + 1，恰好在窗口外
        applyLoopBudget(messages, MAX_LOOP - RunnerConstants.FINAL_TURN_INSTRUCTION_LEAD - 1, MAX_LOOP);

        assertTrue(tailText(messages).startsWith(RunnerConstants.LOOP_BUDGET_PREFIX),
                "窗口外最后一条应是预算段而非硬收口");
    }

    @Test
    void rebuildsBudgetEveryLoop_insteadOfAccumulating() throws Exception {
        List<ChatMessageDTO> messages = baseMessages();

        // 连跑 5 轮，每轮之间夹上 assistant(tool_calls) + tool 结果——真实工具轮就是这样涨的
        for (int loop = 0; loop < 5; loop++) {
            applyLoopBudget(messages, loop, MAX_LOOP);
            assertEquals(1, budgetCount(messages), "第 " + (loop + 1) + " 轮后预算段应恒为 1 份");
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_ASSISTANT).setContent("calling"));
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_TOOL).setToolCallId("t" + loop).setContent("{}"));
        }

        // 最后一条必须是当前轮的（5/128），旧副本不能残留在历史中间
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

    // ---------- 重试退避 ----------

    @Test
    void llmTurnRetryBackoff_growsExponentiallyAndCaps() {
        // 固定 1s 退避撞上模型端的突发窗口，会在同一秒再打一次，把一次抖动放大成连续失败
        assertEquals(0L, SessionRunner.llmTurnRetryBackoffMs(0));
        assertEquals(RunnerConstants.LLM_TURN_RETRY_BASE_MS, SessionRunner.llmTurnRetryBackoffMs(1));
        assertEquals(RunnerConstants.LLM_TURN_RETRY_BASE_MS * 2, SessionRunner.llmTurnRetryBackoffMs(2));
        assertEquals(RunnerConstants.LLM_TURN_RETRY_BASE_MS * 4, SessionRunner.llmTurnRetryBackoffMs(3));

        long capped = SessionRunner.llmTurnRetryBackoffMs(30);
        assertEquals(RunnerConstants.LLM_TURN_RETRY_MAX_MS, capped, "必须封顶，否则重试链会拖垮单轮");
        // 封顶后不再增长
        assertEquals(capped, SessionRunner.llmTurnRetryBackoffMs(50));
    }

    @Test
    void llmTurnRetryBudget_survivesThreeConsecutiveFailures() {
        // 线上实测：有 run 在 9 秒内连撞 3 次就耗尽了旧的 2 次尝试预算而 FAILED，
        // 只有一个恰好只撞 2 次的活了下来。合计 4 次尝试才够。
        int attempts = new AgentRuntimeProperties.RunnerConfig().getLlmTurnMaxRetries() + 1;
        assertEquals(4, attempts, "默认重试预算应为 3 次重试（合计 4 次尝试）");
    }
}
