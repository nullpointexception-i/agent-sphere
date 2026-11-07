package com.buukle.agent.runtime.kernel.loader;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.util.TimeUtils;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.util.json.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Resume recovery:
 * - With a run-local record: summary is always the leading system, and tail is injected only when restoreCurrentRun;
 *   otherwise the current run's tail is kept by SessionRunner to avoid duplication.
 * - Without a run-local record: restore the persisted tool history + per-turn assistant body; reasoning goes through reasoning_content and never enters the body.
 */
@ExtendWith(MockitoExtension.class)
class HistoryLoaderTest {

    private static final long SESSION_ID = 956L;
    private static final long RUN_ID = 2270L;

    @Mock
    RunSpi runSpi;
    @Mock
    AgentCompactRecordSpi compactRecordSpi;
    @Mock
    AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    AgentLlmInteractionRecordSpi interactionSpi;
    @Mock
    ChatAttachmentResolver attachmentResolver;

    private HistoryLoader loader;

    @BeforeEach
    void setUp() {
        loader = new HistoryLoader(runSpi, compactRecordSpi, toolCallRecordSpi, interactionSpi,
                new AgentRuntimeProperties(), attachmentResolver);
        RunVO run = new RunVO();
        run.setId(RUN_ID);
        run.setSessionId(SESSION_ID);
        run.setStatus("RUNNING");
        run.setUserMessage("original task");
        lenient().when(runSpi.listRunsBySessionAfterId(SESSION_ID, 0L)).thenReturn(List.of(run));
        lenient().when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of());
        lenient().when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of());
    }

    private static AgentCompactRecordVO runCompact(String summary, List<ChatMessageDTO> tail) {
        AgentCompactRecordVO vo = new AgentCompactRecordVO();
        vo.setId(9L);
        vo.setRunId(RUN_ID);
        vo.setSummaryAfter(summary);
        vo.setTailJson(JsonUtils.toJson(tail));
        return vo;
    }

    @Test
    void resumeInjectsRunSummaryAndTail() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID))
                .thenReturn(runCompact("RUN-SUM", List.of(user("tail user"))));

        HistoryLoader.HistoryResult result = loader.loadResult(SESSION_ID, RUN_ID, true);
        List<ChatMessageDTO> messages = result.messages();

        assertEquals(2, messages.size());
        assertEquals(1, result.currentRunStart(), "当前 run 起点应指向 tail 的第一条（原始 user）");
        assertTrue(messages.get(0).getContent().toString().startsWith(HistoryLoader.RUN_SUMMARY_PREFIX));
        assertTrue(messages.get(0).getContent().toString().contains("RUN-SUM"));
        assertEquals("tail user", messages.get(1).getContent());
    }

    @Test
    void sessionCompactionReassemblyKeepsSummaryButNotTail() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID))
                .thenReturn(runCompact("RUN-SUM", List.of(user("tail user"))));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, false);

        // Summary only; the tail is provided by SessionRunner's currentRunTail
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getContent().toString().contains("RUN-SUM"));
    }

    @Test
    void resumeWithoutRunCompactRestoresToolHistoryAndAssistantReplyText() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);

        AgentLlmInteractionRecordVO first = interaction("first assistant text", "2026-10-29 04:12:21");
        AgentLlmInteractionRecordVO second = interaction(null, "2026-10-29 04:12:26");
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(first, second));
        // The production SPI returns descending order; HistoryLoader reverses it to chronological order internally
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(
                toolCall(2L, "call-2", "04:12:28"),
                toolCall(1L, "call-1", "04:12:22")));

        HistoryLoader.HistoryResult result = loader.loadResult(SESSION_ID, RUN_ID, true);
        List<ChatMessageDTO> messages = result.messages();

        assertEquals(0, result.currentRunStart());
        assertEquals("original task", messages.get(0).getContent());
        ChatMessageDTO firstAssistant = messages.get(1);
        assertEquals("assistant", firstAssistant.getRole());
        assertEquals("first assistant text", firstAssistant.getContent());
        assertEquals("call-1", firstAssistant.getToolCalls().get(0).getId());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("call-1", messages.get(2).getToolCallId());
        // The second turn has no body: only assistant(tool_calls) + tool
        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("call-2", messages.get(3).getToolCalls().get(0).getId());
        assertEquals("tool", messages.get(4).getRole());
        assertEquals("call-2", messages.get(4).getToolCallId());
    }

    @Test
    void toolHistoryArgsStripCallReason() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("assistant text", "2026-10-29 04:12:21")));
        AgentToolCallRecordVO call = toolCall(1L, "call-1", "04:12:22");
        call.setArgumentsJson("{\"url\":\"https://example.com\",\"_reason\":\"检查页面\"}");
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(call));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        ChatMessageDTO assistant = messages.stream()
                .filter(m -> "assistant".equals(m.getRole()) && m.getToolCalls() != null)
                .findFirst().orElseThrow();
        String args = assistant.getToolCalls().get(0).getFunction().getArguments();
        assertFalse(args.contains("_reason"), "模型历史不应重复携带调用理由");
        assertTrue(args.contains("https://example.com"));
    }

    @Test
    void browserHistoryKeepsFullTextBelowThreshold() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("first assistant text", "2026-10-29 04:12:21"),
                interaction("second assistant text", "2026-10-29 04:12:26")));

        String oldArtifact = "{\"data\":{\"_url\":\"https://example.com/a\",\"matches\":["
                + "{\"text\":\"news-a\"},{\"text\":\"news-b\"},"
                + "{\"text\":\"news-c\"},{\"text\":\"news-d\"}]}}";
        AgentToolCallRecordVO oldCall = toolCall(1L, "call-old", "04:12:22");
        oldCall.setArtifact(oldArtifact);
        oldCall.setCompressedArtifact("collapsed-old");
        AgentToolCallRecordVO newCall = toolCall(2L, "call-new", "04:12:28");
        newCall.setArtifact("new-full");
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(newCall, oldCall));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        String oldContent = messages.stream()
                .filter(m -> "call-old".equals(m.getToolCallId()))
                .map(m -> String.valueOf(m.getContent()))
                .findFirst().orElse("");
        String newContent = messages.stream()
                .filter(m -> "call-new".equals(m.getToolCallId()))
                .map(m -> String.valueOf(m.getContent()))
                .findFirst().orElse("");
        assertEquals(oldArtifact, oldContent, "小页面应恢复完整原文，而不是摘要/压缩结果");
        assertEquals("new-full", newContent);
    }

    @Test
    void browserHistoryUsesCallIdReferenceForLargePage() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("assistant text", "2026-10-29 04:12:21")));

        AgentToolCallRecordVO large = toolCall(1L, "call-large", "04:12:22");
        large.setArtifact("x".repeat(20_001));
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(large));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        String content = messages.stream()
                .filter(m -> "call-large".equals(m.getToolCallId()))
                .map(m -> String.valueOf(m.getContent()))
                .findFirst().orElse("");
        assertTrue(content.contains("read_tool_result"));
        assertTrue(content.contains("call-large"));
    }

    @Test
    void missingCompressedArtifactFallsBackToRawArtifact() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("assistant text", "2026-10-29 04:12:21")));
        AgentToolCallRecordVO call = toolCall(1L, "call-raw", "04:12:22");
        call.setArtifact("raw-full");
        call.setCompressedArtifact(null);
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(call));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        String content = messages.stream()
                .filter(m -> "call-raw".equals(m.getToolCallId()))
                .map(m -> String.valueOf(m.getContent()))
                .findFirst().orElse("");
        assertEquals("raw-full", content);
    }

    @Test
    void resumeKeepsReasoningOutOfVisibleContent() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        AgentLlmInteractionRecordVO first = interaction("visible reply", "2026-10-29 04:12:21");
        first.setReasoning("secret reasoning");
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(first));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        ChatMessageDTO assistant = messages.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .findFirst().orElseThrow();
        assertEquals("visible reply", assistant.getContent());
        assertEquals("secret reasoning", assistant.getReasoningContent());
        assertFalse(messages.stream().anyMatch(m ->
                m.getContent() instanceof String s && s.contains("secret reasoning")));
    }

    @Test
    void resumeReplaysRecentScreenshotObservation() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("assistant text", "2026-10-29 04:12:21")));
        AgentToolCallRecordVO call = toolCall(1L, "call-shot", "04:12:22");
        call.setArtifact("{\"data\":{\"screenshot\":{\"fileKey\":\"f1\",\"width\":100,\"height\":200}}}");
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(call));
        when(attachmentResolver.toDataUrl("f1")).thenReturn("data:image/jpeg;base64,AAA");

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        ChatMessageDTO observation = messages.stream()
                .filter(m -> "user".equals(m.getRole()) && m.getContent() instanceof List)
                .findFirst().orElseThrow();
        List<?> parts = (List<?>) observation.getContent();
        assertTrue(parts.stream().anyMatch(p -> p instanceof ChatMessagePartDTO part
                && ChatMessagePartDTO.TYPE_IMAGE_URL.equals(part.getType())));
    }

    @Test
    void screenshotReplayIsSkippedWhenReplayDisabled() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);

        // restoreCurrentRun=false: session compaction reassembly does not replay screenshots or rebuild the current run
        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, false);

        assertTrue(messages.stream().noneMatch(m -> "user".equals(m.getRole())
                && m.getContent() instanceof List));
    }

    @Test
    void unreadableTailFallsBackToToolHistory() {        AgentCompactRecordVO compact = runCompact("RUN-SUM", List.of(user("tail user")));
        compact.setTailJson("not-json");
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(compact);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("first assistant text", "2026-10-29 04:12:21")));
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(
                toolCall(1L, "call-1", "2026-10-29 04:12:22")));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        assertTrue(messages.stream().anyMatch(m -> m.getRole().equals("tool")),
                "tail 损坏时回退到持久化 tool 历史");
    }

    @Test
    void toolCallInSameSecondAsNextTurnKeepsPreviousTurn() {
        // Production run 2810: the tool was recorded at 03:54:51.000042 and belongs to turn16 (03:54:49.986679);
        // but after second-level truncation it matches turn17's start time (03:54:51.842411), so the old implementation misassigned it to turn17,
        // making one assistant carry two tool_calls and get rejected with 400 by the model side.
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        LocalDateTime turn16At = LocalDateTime.of(2026, 11, 3, 3, 54, 49, 986_679_000);
        LocalDateTime toolAt = LocalDateTime.of(2026, 11, 3, 3, 54, 51, 0);
        LocalDateTime turn17At = LocalDateTime.of(2026, 11, 3, 3, 54, 51, 842_411_000);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interactionAt("turn 16", turn16At), interactionAt("turn 17", turn17At)));
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(
                toolCallAt(1L, "call-fepcb", toolAt, null)));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        ChatMessageDTO assistant16 = messages.get(1);
        assertEquals("turn 16", assistant16.getContent());
        assertEquals(List.of("call-fepcb"),
                assistant16.getToolCalls().stream().map(ToolCallDTO::getId).toList());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("call-fepcb", messages.get(2).getToolCallId());
        assertEquals("turn 17", messages.get(3).getContent());
        assertNull(messages.get(3).getToolCalls(), "turn 17 不应吞掉上一轮产生的 tool call");
    }

    @Test
    void screenshotObservationIsAppendedAfterAllToolMessagesOfTurn() {
        // With multiple tool calls in one turn, the screenshot observation must come after all tool messages of that turn,
        // otherwise assistant(tool_calls) → tool → user(observation) → tool is judged an illegal sequence.
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        LocalDateTime turnAt = LocalDateTime.of(2026, 11, 3, 3, 54, 49, 0);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(interactionAt("turn", turnAt)));
        AgentToolCallRecordVO first = toolCallAt(1L, "call-a",
                LocalDateTime.of(2026, 11, 3, 3, 54, 50, 0), null);
        AgentToolCallRecordVO second = toolCallAt(2L, "call-b",
                LocalDateTime.of(2026, 11, 3, 3, 54, 50, 500_000_000),
                "{\"data\":{\"screenshot\":{\"fileKey\":\"f1\",\"width\":100,\"height\":200}}}");
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(second, first));
        when(attachmentResolver.toDataUrl("f1")).thenReturn("data:image/jpeg;base64,AAA");

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("call-a", messages.get(2).getToolCallId());
        assertEquals("tool", messages.get(3).getRole());
        assertEquals("call-b", messages.get(3).getToolCallId());
        assertEquals("user", messages.get(4).getRole(), "截图观察应排在全部 tool 消息之后");
        assertTrue(messages.get(4).getContent() instanceof List);
    }

    @Test
    void toolCallPairingCheckRejectsObservationInsertedBetweenToolMessages() {
        ChatMessageDTO assistant = new ChatMessageDTO()
                .setRole("assistant")
                .setToolCalls(List.of(toolCallDto("a"), toolCallDto("b")));
        ChatMessageDTO toolA = new ChatMessageDTO().setRole("tool").setToolCallId("a");
        ChatMessageDTO observation = new ChatMessageDTO().setRole("user").setContent("shot");
        ChatMessageDTO toolB = new ChatMessageDTO().setRole("tool").setToolCallId("b");

        assertFalse(HistoryLoader.hasValidToolCallPairing(
                List.of(assistant, toolA, observation, toolB)));
        assertTrue(HistoryLoader.hasValidToolCallPairing(
                List.of(assistant, toolA, toolB, observation)));
    }

    @Test
    void toolCallWithTurnNoIsPairedByTurnNotByTimestamp() {
        // With turn_no present, time is ignored: this call's time falls in turn1's window, but turn_no=0 must assign it to turn0.
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        AgentLlmInteractionRecordVO turn0 = interactionAt("turn 0",
                LocalDateTime.of(2026, 11, 3, 3, 54, 49, 0));
        turn0.setTurnNo(0);
        AgentLlmInteractionRecordVO turn1 = interactionAt("turn 1",
                LocalDateTime.of(2026, 11, 3, 3, 54, 51, 0));
        turn1.setTurnNo(1);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(turn0, turn1));
        AgentToolCallRecordVO call = toolCallAt(1L, "call-a",
                LocalDateTime.of(2026, 11, 3, 3, 54, 52, 0), null);
        call.setTurnNo(0);
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(call));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        assertEquals("turn 0", messages.get(1).getContent());
        assertEquals(List.of("call-a"),
                messages.get(1).getToolCalls().stream().map(ToolCallDTO::getId).toList());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("call-a", messages.get(2).getToolCallId());
        assertEquals("turn 1", messages.get(3).getContent());
        assertNull(messages.get(3).getToolCalls());
    }

    @Test
    void toolCallWithoutTurnNoFallsBackToTimeWindow() {
        // Historical rows predating the migration have no turn_no: still pair by millisecond window; a missing field must not drop the call.
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        AgentLlmInteractionRecordVO turn0 = interactionAt("turn 0",
                LocalDateTime.of(2026, 11, 3, 3, 54, 49, 0));
        turn0.setTurnNo(0);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(turn0));
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID)).thenReturn(List.of(
                toolCallAt(1L, "call-legacy", LocalDateTime.of(2026, 11, 3, 3, 54, 50, 0), null)));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        assertEquals(List.of("call-legacy"),
                messages.get(1).getToolCalls().stream().map(ToolCallDTO::getId).toList());
        assertEquals("call-legacy", messages.get(2).getToolCallId());
    }

    @Test
    void subAgentToolCallsAreNotReplayedIntoParentHistory() {
        // Sub-agent tool records share run_id with the parent run (distinguished by sub_agent_run_id),
        // but they are not part of the parent Agent's conversation: the parent only ever saw one delegate result.
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(
                interaction("assistant text", "2026-10-29 04:12:21")));
        AgentToolCallRecordVO parentCall = toolCall(1L, "call-parent", "04:12:22");
        AgentToolCallRecordVO subCall = toolCall(2L, "call-sub", "04:12:23");
        subCall.setSubAgentRunId(77L);
        when(toolCallRecordSpi.listBySessionId(SESSION_ID, RUN_ID))
                .thenReturn(List.of(subCall, parentCall));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        List<String> callIds = messages.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .map(m -> m.getToolCallId())
                .toList();
        assertEquals(List.of("call-parent"), callIds);
    }

    private static ChatMessageDTO user(String text) {
        return new ChatMessageDTO().setRole("user").setContent(text);
    }

    private static AgentLlmInteractionRecordVO interaction(String reply, String createdAt) {
        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setRunId(RUN_ID);
        vo.setInteractionType("CHAT_REPLY");
        vo.setReplyContent(reply);
        vo.setCreatedAt(createdAt);
        return vo;
    }

    private static AgentToolCallRecordVO toolCall(Long id, String callId, String time) {
        AgentToolCallRecordVO vo = new AgentToolCallRecordVO();
        vo.setId(id);
        vo.setCallId(callId);
        vo.setRunId(RUN_ID);
        vo.setSessionId(SESSION_ID);
        vo.setToolName("builtin_5");
        vo.setArgumentsJson("{}");
        vo.setCompressedArtifact("result-" + id);
        vo.setStatus("SUCCEEDED");
        vo.setCreatedAt("2026-10-29 " + time);
        return vo;
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Interaction record with millisecond precision (the real VO is filled by the mapper with both the createdAt string and createdAtMillis). */
    private static AgentLlmInteractionRecordVO interactionAt(String reply, LocalDateTime at) {
        AgentLlmInteractionRecordVO vo = interaction(reply, at.format(TS_FMT));
        vo.setCreatedAtMillis(TimeUtils.toEpochMillis(at));
        return vo;
    }

    private static AgentToolCallRecordVO toolCallAt(Long id, String callId, LocalDateTime at, String artifact) {
        AgentToolCallRecordVO vo = new AgentToolCallRecordVO();
        vo.setId(id);
        vo.setCallId(callId);
        vo.setRunId(RUN_ID);
        vo.setSessionId(SESSION_ID);
        vo.setToolName("builtin_5");
        vo.setArgumentsJson("{}");
        vo.setCompressedArtifact("result-" + id);
        vo.setStatus("SUCCEEDED");
        vo.setArtifact(artifact);
        vo.setCreatedAt(at.format(TS_FMT));
        vo.setCreatedAtMillis(TimeUtils.toEpochMillis(at));
        return vo;
    }

    private static ToolCallDTO toolCallDto(String id) {
        return ToolCallDTO.builder().id(id).type("function").build();
    }
}
