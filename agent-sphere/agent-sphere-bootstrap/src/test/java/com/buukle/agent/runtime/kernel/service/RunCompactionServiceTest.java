package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.complete.FinishReason;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.util.json.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单 run 压缩：保留当前 user + 最近 3 条作为 tail，旧消息摘要为 leading system。 */
@ExtendWith(MockitoExtension.class)
class RunCompactionServiceTest {

    private static final long SESSION_ID = 956L;
    private static final long RUN_ID = 2270L;

    @Mock
    RunSpi runSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    KernelLlmService kernelLlmService;
    @Mock
    ApiKeySpi apiKeySpi;
    @Mock
    AgentCompactRecordSpi compactRecordSpi;
    @Mock
    AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    RouteListBuilder routeListBuilder;
    @Mock
    LlmRequestConfigurer llmRequestConfigurer;

    private CompactionService service;
    private ModelRouteFullVO route;
    private final java.util.concurrent.atomic.AtomicReference<ChatCompletionRequestDTO> lastRequest =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getRunner().getCompaction().setBudgetRatio(0.7);
        props.getLlm().setStreamTimeout(Duration.ofSeconds(30));
        service = new CompactionService(props, runSpi, sessionSpi, kernelLlmService, apiKeySpi,
                compactRecordSpi, toolCallRecordSpi, eventPublisher, routeListBuilder,
                new FallbackRouteExecutor(), llmRequestConfigurer);

        route = new ModelRouteFullVO();
        route.setId(33L);
        route.setCompany("openai");
        route.setBaseUrl("http://fixture.local");
        route.setModelName("fixture-model");
        route.setMaxInputTokens(32_000L);
        route.setMaxOutputTokens(4_096L);

        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setAgentInstanceId(2L);
        lenient().when(sessionSpi.getSession(SESSION_ID)).thenReturn(session);
        lenient().when(routeListBuilder.fromInstance(2L)).thenReturn(List.of(route));
        lenient().when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        AgentCompactRecordVO record = new AgentCompactRecordVO();
        record.setId(99L);
        lenient().when(compactRecordSpi.createRunRecord(SESSION_ID, RUN_ID)).thenReturn(record);

        lenient().when(kernelLlmService.stream(anyString(), anyString(), any(), anyString(),
                any(), any(), any())).thenAnswer(inv -> {
            lastRequest.set(inv.getArgument(4));
            @SuppressWarnings("unchecked")
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            onEvent.accept(new LLMEvent.TextDelta("RUN-SUM"));
            return CompletableFuture.completedFuture(null);
        });
    }

    private static ChatMessageDTO msg(String role, String content) {
        return new ChatMessageDTO().setRole(role).setContent(content);
    }

    private static List<ChatMessageDTO> messages() {
        List<ChatMessageDTO> messages = new ArrayList<>();
        messages.add(msg("system", "SYS"));
        messages.add(msg("user", "original task"));
        messages.add(msg("assistant", "a2".repeat(3_000)));
        messages.add(msg("tool", "t3".repeat(3_000)));
        messages.add(msg("assistant", "a4".repeat(3_000)));
        messages.add(msg("tool", "t5".repeat(3_000)));
        messages.add(msg("assistant", "a6".repeat(3_000)));
        messages.add(msg("tool", "t7".repeat(3_000)));
        return messages;
    }

    @Test
    void compactRunKeepsUserAndLastThreeMessagesAsTail() {
        CompactionService.RunCompactionResult result = service.compactRun(
                SESSION_ID, RUN_ID, null, messages(), 1, null, null);

        assertEquals(CompactionOutcome.COMPLETED, result.outcome());
        // tail 预算只够最近一组：user + a6 + t7；更旧的 a2/t3/a4/t5 进入摘要
        assertEquals(5, result.messages().size());
        assertEquals(2, result.currentRunStart());
        assertTrue(result.messages().get(1).getContent().toString().contains("RUN-SUM"));
        assertEquals("original task", result.messages().get(2).getContent());
        assertTrue(result.messages().get(3).getContent().toString().startsWith("a6"));
        assertTrue(result.messages().get(4).getContent().toString().startsWith("t7"));

        verify(compactRecordSpi).completeRunCompact(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("RUN-SUM"),
                any(), anyString());
    }

    @Test
    void compactRunPersistsTailJsonWithoutImages() {
        List<ChatMessageDTO> messages = messages();
        // tail 内的图片 data URL 必须被替换
        messages.set(6, new ChatMessageDTO().setRole("assistant").setContent(List.of(
                new com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO()
                        .setType("image_url")
                        .setImageUrl(new com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO.ImageUrl()
                                .setUrl("data:image/png;base64,AAAA")))));

        service.compactRun(SESSION_ID, RUN_ID, null, messages, 1, null, null);

        org.mockito.ArgumentCaptor<String> tail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(compactRecordSpi).completeRunCompact(any(), any(), any(), any(), tail.capture());
        assertFalse(tail.getValue().contains("data:image"), "tail_json 不能包含图片 data URL");
        assertTrue(tail.getValue().contains("[image omitted after run compaction]"));
        assertTrue(tail.getValue().contains("original task"));
        assertEquals(5, JsonUtils.parse(tail.getValue(), new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {}).size());
    }

    @Test
    void compactionDisablesThinking() {
        service.compactRun(SESSION_ID, RUN_ID, null, messages(), 1, null, null);

        org.junit.jupiter.api.Assertions.assertNotNull(lastRequest.get());
        org.junit.jupiter.api.Assertions.assertEquals("disabled",
                lastRequest.get().getThinking().getType());
    }

    @Test
    void tailExcludesDynamicSystemSlots() {
        List<ChatMessageDTO> messages = new ArrayList<>(messages());
        messages.add(msg("system", RunnerConstants.LOOP_BUDGET_PREFIX + "Loop 1/10"));
        messages.add(msg("system", RunnerConstants.PROMPT_TODOLIST_HEADER + "{\"todos\":[]}"));
        messages.add(msg("system", "\n\nCurrent server time: legacy"));

        CompactionService.RunCompactionResult result = service.compactRun(
                SESSION_ID, RUN_ID, null, messages, 1, null, null);

        assertEquals(CompactionOutcome.COMPLETED, result.outcome());
        assertEquals("original task", result.messages().get(result.currentRunStart()).getContent());
        org.junit.jupiter.api.Assertions.assertTrue(result.messages().stream().noneMatch(m ->
                m.getContent() instanceof String s && (s.contains("Current server time")
                        || s.contains(RunnerConstants.PROMPT_TODOLIST_HEADER.trim())
                        || s.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX))));
    }

    @Test
    void summaryCapIsRouteAware() {
        // 32k 路由：min(10000, 32000*0.12)=3840；128k / 1M 路由都封顶 10000
        org.junit.jupiter.api.Assertions.assertEquals(3840, service.resolveSummaryCap(32_000L));
        org.junit.jupiter.api.Assertions.assertEquals(10000, service.resolveSummaryCap(128_000L));
        org.junit.jupiter.api.Assertions.assertEquals(10000, service.resolveSummaryCap(1_000_000L));
    }

    @Test
    void summaryOverTargetIsCompressedAgain() {
        org.mockito.Mockito.reset(kernelLlmService);
        String tooLong = "x".repeat(20_000);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(kernelLlmService.stream(anyString(), anyString(), any(), anyString(),
                any(), any(), any())).thenAnswer(inv -> {
            lastRequest.set(inv.getArgument(4));
            @SuppressWarnings("unchecked")
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            onEvent.accept(new LLMEvent.TextDelta(
                    calls.incrementAndGet() == 1 ? tooLong : "## Goal\nshort"));
            onEvent.accept(new LLMEvent.Finish(FinishReason.STOP,
                    java.util.Map.of("completion_tokens", 100)));
            return CompletableFuture.completedFuture(null);
        });

        CompactionService.RunCompactionResult result = service.compactRun(
                SESSION_ID, RUN_ID, null, messages(), 1, null, null);

        assertEquals(CompactionOutcome.COMPLETED, result.outcome());
        assertEquals(2, calls.get(), "首轮超 10k 后必须把返回值再压缩一次");
        verify(compactRecordSpi).completeRunCompact(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("## Goal\nshort"),
                any(), anyString());
    }

    @Test
    void summaryStillOverTargetAfterRoundsIsTrimmed() {
        org.mockito.Mockito.reset(kernelLlmService);
        String tooLong = ("x".repeat(100) + "。").repeat(200);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(kernelLlmService.stream(anyString(), anyString(), any(), anyString(),
                any(), any(), any())).thenAnswer(inv -> {
            lastRequest.set(inv.getArgument(4));
            @SuppressWarnings("unchecked")
            Consumer<LLMEvent> onEvent = inv.getArgument(5);
            calls.incrementAndGet();
            onEvent.accept(new LLMEvent.TextDelta(tooLong));
            onEvent.accept(new LLMEvent.Finish(FinishReason.LENGTH,
                    java.util.Map.of("completion_tokens", 4096)));
            return CompletableFuture.completedFuture(null);
        });

        CompactionService.RunCompactionResult result = service.compactRun(
                SESSION_ID, RUN_ID, null, messages(), 1, null, null);

        assertEquals(CompactionOutcome.COMPLETED, result.outcome());
        assertEquals(3, calls.get(), "最多 3 轮压缩");
        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(compactRecordSpi).completeRunCompact(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.isNull(), summary.capture(), any(), anyString());
        assertTrue(com.buukle.agent.runtime.kernel.util.SummarySanitizer.isComplete(summary.getValue()));
        assertTrue(summary.getValue().length() <= 3840 * com.buukle.agent.runtime.kernel.constants.RunnerConstants.TOKEN_ESTIMATE_DIVISOR);
    }

    @Test
    void snapshotRunTailUpdatesCurrentRunTail() {
        AgentCompactRecordVO latest = new AgentCompactRecordVO();
        latest.setId(9L);
        latest.setRunId(RUN_ID);
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(latest);

        service.snapshotRunTail(RUN_ID, messages(), 1);

        org.mockito.ArgumentCaptor<String> tail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(compactRecordSpi).updateRunTail(org.mockito.ArgumentMatchers.eq(RUN_ID), tail.capture());
        assertTrue(tail.getValue().contains("original task"));
        assertTrue(tail.getValue().contains("t3"));
        assertTrue(tail.getValue().contains("t7"));
    }

    @Test
    void snapshotRunTailIsNoopWithoutRunRecord() {
        service.snapshotRunTail(RUN_ID, messages(), 1);

        verify(compactRecordSpi, org.mockito.Mockito.never()).updateRunTail(any(), any());
    }

    @Test
    void compactRunSkipsWhenCurrentRunHasNoOlderMessages() {
        List<ChatMessageDTO> messages = List.of(
                msg("system", "SYS"),
                msg("user", "original task"),
                msg("assistant", "a2"),
                msg("tool", "t3"),
                msg("assistant", "a4"));

        CompactionService.RunCompactionResult result = service.compactRun(
                SESSION_ID, RUN_ID, null, messages, 1, null, null);

        assertEquals(CompactionOutcome.NO_PROGRESS, result.outcome());
    }
}
