package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
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
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.TitleService;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RMapCache;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * L1/L2 端到端：agentic run 只能用 `finish` 收口，沉默是异常。
 *
 * <p>用户反馈的核心场景：小模型写一大段话就停下、一个工具也不调，旧循环把「没有工具调用」
 * 当成「做完了」，任务静默失败。现在的判据是三分支：
 * <ul>
 *   <li>本轮含 finish → 完成（带声明的 summary）</li>
 *   <li>有其它工具调用 → 继续</li>
 *   <li>完全没有工具调用（且已用过工具）→ 追问，连续超限判 FAILED</li>
 * </ul>
 * 纯聊天（从未用过工具）保持原语义：回文本即完成。
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

    private RunVO run;

    /** 建好 RunVO + Redisson 上下文 + 路由，返回可执行的 SessionRunner。 */
    private SessionRunner newRunner(int maxLoopCount, String userMessage) {
        AgentRuntimeProperties.RunnerConfig runnerConfig = new AgentRuntimeProperties.RunnerConfig();
        runnerConfig.setMaxLoopCount(maxLoopCount);
        lenient().when(properties.getRunner()).thenReturn(runnerConfig);
        lenient().when(properties.getTool()).thenReturn(new AgentRuntimeProperties.ToolConfig());

        run = new RunVO();
        run.setId(RUN_ID);
        run.setSessionId(SESSION_ID);
        run.setStatus("RUNNING");
        lenient().when(runSpi.getRun(RUN_ID)).thenReturn(run);

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
        @SuppressWarnings("unchecked")
        RMapCache<Long, KernelContext> cache = mock(RMapCache.class);
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

        return new SessionRunner(properties, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                eventPublisher, routeListBuilder, new FallbackRouteExecutor(), historyLoader,
                compactionService, inputManager, new RunPromptBuilder(), toolExecutor, titleService,
                toolCallRecordSpi, redis, attachmentResolver,
                new LlmRequestConfigurer(mock(SystemConfigSpi.class)), mock(ToolApprovalGrantSpi.class), mock(ToolApprovalService.class));
    }

    /** 按轮次脚本化模型输出；turn 从 0 开始。 */
    private void scriptModel(java.util.function.IntFunction<LLMEvent[]> script) {
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
    }

    @Test
    void finishTool_completesRunWithSummary_andIsNotLoopCapped() {
        // maxLoopCount=2：第 2 轮（loopCount=1）就是「最后一轮」，验证 finish 不被当成 loop cap
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
        verify(inputManager).clear(SESSION_ID);
    }
}
