package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
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
        messages.add(msg("assistant", "a2"));
        messages.add(msg("tool", "t3"));
        messages.add(msg("assistant", "a4"));
        messages.add(msg("tool", "t5"));
        messages.add(msg("assistant", "a6"));
        messages.add(msg("tool", "t7"));
        return messages;
    }

    @Test
    void compactRunKeepsUserAndLastThreeMessagesAsTail() {
        CompactionService.RunCompactionResult result = service.compactRun(
                SESSION_ID, RUN_ID, null, messages(), 1, null);

        assertEquals(CompactionOutcome.COMPLETED, result.outcome());
        // 初始 tailStart 落在 tool 上，会回退到该组 assistant，避免孤儿 tool 消息
        assertEquals(7, result.messages().size());
        assertEquals(2, result.currentRunStart());
        assertTrue(result.messages().get(1).getContent().toString().contains("RUN-SUM"));
        assertEquals("original task", result.messages().get(2).getContent());
        // tail = user + a4/t5/a6/t7
        assertEquals("a4", result.messages().get(3).getContent());
        assertEquals("t5", result.messages().get(4).getContent());
        assertEquals("a6", result.messages().get(5).getContent());
        assertEquals("t7", result.messages().get(6).getContent());

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

        service.compactRun(SESSION_ID, RUN_ID, null, messages, 1, null);

        org.mockito.ArgumentCaptor<String> tail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(compactRecordSpi).completeRunCompact(any(), any(), any(), any(), tail.capture());
        assertFalse(tail.getValue().contains("data:image"), "tail_json 不能包含图片 data URL");
        assertTrue(tail.getValue().contains("[image omitted after run compaction]"));
        assertTrue(tail.getValue().contains("original task"));
        assertEquals(5, JsonUtils.parse(tail.getValue(), new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {}).size());
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
                SESSION_ID, RUN_ID, null, messages, 1, null);

        assertEquals(CompactionOutcome.NO_PROGRESS, result.outcome());
    }
}
