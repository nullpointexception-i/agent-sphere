package com.buukle.agent.runtime.kernel.loader;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.util.json.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Resume 恢复：
 * - 有 run-local 记录：summary 始终作为 leading system，tail 只在 restoreCurrentRun 时注入；
 *   否则当前 run 的尾部由 SessionRunner 保留，避免重复。
 * - 无 run-local 记录：恢复持久化 tool 历史 + 逐轮 assistant 正文；reasoning 不进入上下文。
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

    private HistoryLoader loader;

    @BeforeEach
    void setUp() {
        loader = new HistoryLoader(runSpi, compactRecordSpi, toolCallRecordSpi, interactionSpi,
                new AgentRuntimeProperties());
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

        // 只有 summary；tail 由 SessionRunner 的 currentRunTail 提供
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getContent().toString().contains("RUN-SUM"));
    }

    @Test
    void resumeWithoutRunCompactRestoresToolHistoryAndAssistantReplyText() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);

        AgentLlmInteractionRecordVO first = interaction("first assistant text", "2026-10-29 04:12:21");
        AgentLlmInteractionRecordVO second = interaction(null, "2026-10-29 04:12:26");
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(first, second));
        // 生产 SPI 返回倒序，HistoryLoader 内部 reverse 成时间正序
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
        // 第二轮没有正文：只有 assistant(tool_calls) + tool
        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("call-2", messages.get(3).getToolCalls().get(0).getId());
        assertEquals("tool", messages.get(4).getRole());
        assertEquals("call-2", messages.get(4).getToolCallId());
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
    void resumeNeverIncludesReasoning() {
        when(compactRecordSpi.getLatestCompletedByRunId(RUN_ID)).thenReturn(null);
        AgentLlmInteractionRecordVO first = interaction("visible reply", "2026-10-29 04:12:21");
        first.setReasoning("secret reasoning");
        when(interactionSpi.listByRunIdAsc(RUN_ID)).thenReturn(List.of(first));

        List<ChatMessageDTO> messages = loader.load(SESSION_ID, RUN_ID, true);

        assertFalse(messages.stream().anyMatch(m ->
                m.getContent() instanceof String s && s.contains("secret reasoning")));
    }

    @Test
    void unreadableTailFallsBackToToolHistory() {
        AgentCompactRecordVO compact = runCompact("RUN-SUM", List.of(user("tail user")));
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
}
