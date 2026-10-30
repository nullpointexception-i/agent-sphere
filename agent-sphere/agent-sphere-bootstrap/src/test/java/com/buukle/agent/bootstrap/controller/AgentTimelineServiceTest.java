package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.domain.AgentTimeline;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.enums.TimelineContentKey;
import com.buukle.agent.instance.dtvo.vo.AgentTimelineVO;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.domain.AgentCompactRecord;
import com.buukle.agent.instance.repository.AgentCompactRecordMapper;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.service.MessageFeedbackService;
import com.buukle.agent.instance.service.impl.AgentTimelineServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AgentTimelineServiceTest {

    @Mock
    AgentToolCallRecordMapper toolCallMapper;
    @Mock
    AgentCompactRecordMapper compactRecordMapper;
    @Mock
    AgentLlmInteractionRecordMapper interactionMapper;
    @Mock
    AgentPendingClarificationMapper clarificationMapper;
    @Mock
    MessageFeedbackService messageFeedbackService;

    @Test
    void parseAttachments_validJson_returnsList() {
        List<RunAttachment> list = AgentTimelineServiceImpl.parseAttachments(
                "[{\"fileKey\":\"k1\",\"contentType\":\"image/png\"},{\"fileKey\":\"k2\",\"contentType\":\"image/jpeg\"}]");

        assertEquals(2, list.size());
        assertEquals(new RunAttachment("k1", "image/png"), list.get(0));
        assertEquals(new RunAttachment("k2", "image/jpeg"), list.get(1));
    }

    @Test
    void parseAttachments_nullOrBlank_returnsEmpty() {
        assertEquals(0, AgentTimelineServiceImpl.parseAttachments(null).size());
        assertEquals(0, AgentTimelineServiceImpl.parseAttachments("").size());
        assertEquals(0, AgentTimelineServiceImpl.parseAttachments("   ").size());
    }

    @Test
    void parseAttachments_invalidJson_returnsEmpty() {
        assertEquals(0, AgentTimelineServiceImpl.parseAttachments("not json").size());
    }

    @Test
    void resolveContent_toolScreenshot_includesImages() throws Exception {
        AgentToolCallRecord rec = new AgentToolCallRecord();
        rec.setArtifact("{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"shot-1\",\"contentType\":\"image/jpeg\"}}}");
        given(toolCallMapper.selectById(99L)).willReturn(rec);

        AgentTimeline row = new AgentTimeline();
        row.setKind("tool");
        row.setRefToolCallId(99L);

        Map<String, Object> content = invokeResolveContent(row);

        assertEquals(1, ((List<?>) content.get(TimelineContentKey.IMAGES.getCode())).size());
        RunAttachment att = (RunAttachment) ((List<?>) content.get(TimelineContentKey.IMAGES.getCode())).get(0);
        assertEquals(new RunAttachment("shot-1", "image/jpeg"), att);
    }

    @Test
    void resolveContent_toolNoScreenshot_noImages() throws Exception {
        AgentToolCallRecord rec = new AgentToolCallRecord();
        rec.setArtifact("{\"success\":true,\"data\":{\"click\":{\"ok\":true}}}");
        given(toolCallMapper.selectById(99L)).willReturn(rec);

        AgentTimeline row = new AgentTimeline();
        row.setKind("tool");
        row.setRefToolCallId(99L);

        Map<String, Object> content = invokeResolveContent(row);

        assertNull(content.get(TimelineContentKey.IMAGES.getCode()));
    }

    @Test
    void resolveContent_clarification_includesOptionsDescriptionAndResponse() throws Exception {
        AgentPendingClarification rec = new AgentPendingClarification();
        rec.setClarificationId("c-1");
        rec.setTitle("选择方向");
        rec.setType("choice");
        rec.setOptions("[{\"label\":\"A\",\"value\":\"a\"}]");
        rec.setDescription("请选择后继续");
        rec.setUserResponse("{\"selected\":[\"a\"],\"text\":\"\"}");
        given(clarificationMapper.selectById(218L)).willReturn(rec);

        AgentTimeline row = new AgentTimeline();
        row.setKind("clarification");
        row.setRefClarificationId(218L);

        Map<String, Object> content = invokeResolveContent(row);

        assertEquals("[{\"label\":\"A\",\"value\":\"a\"}]", content.get(TimelineContentKey.OPTIONS.getCode()));
        assertEquals("请选择后继续", content.get(TimelineContentKey.DESCRIPTION.getCode()));
        assertEquals("{\"selected\":[\"a\"],\"text\":\"\"}", content.get(TimelineContentKey.RESPONSE.getCode()));
    }

    @Test
    void resolveContent_assistantWithoutReply_doesNotExposeReasoningAsReply() throws Exception {
        AgentLlmInteractionRecord rec = new AgentLlmInteractionRecord();
        rec.setId(107227L);
        rec.setReasoning("The user asks ... ambiguous.");
        rec.setReplyContent(null);
        rec.setResponseBody("The user asks ... ambiguous.");
        given(interactionMapper.selectById(107227L)).willReturn(rec);

        AgentTimeline row = new AgentTimeline();
        row.setKind("assistant");
        row.setRefInteractionId(107227L);

        Map<String, Object> content = invokeResolveContent(row);

        assertEquals("The user asks ... ambiguous.", content.get(TimelineContentKey.THINKING.getCode()));
        assertNull(content.get(TimelineContentKey.REPLY.getCode()),
                "response_body 合并了 reasoning，不能回退成可见回复");
    }

    @Test
    void resolveContent_compaction_usesSummaryAfter() throws Exception {
        AgentCompactRecord rec = new AgentCompactRecord();
        rec.setSummaryAfter("## Goal\n压缩后的摘要");
        given(compactRecordMapper.selectById(7L)).willReturn(rec);

        AgentTimeline row = new AgentTimeline();
        row.setKind("compaction");
        row.setTitle("上下文已压缩");
        row.setRefCompactId(7L);

        Map<String, Object> content = invokeResolveContent(row);

        assertEquals("## Goal\n压缩后的摘要", content.get(TimelineContentKey.TEXT.getCode()));
    }

    @Test
    void resolveContent_compaction_withoutRecord_fallsBackToTitle() throws Exception {
        AgentTimeline row = new AgentTimeline();
        row.setKind("compaction");
        row.setTitle("正在压缩上下文…");

        Map<String, Object> content = invokeResolveContent(row);

        assertEquals("正在压缩上下文…", content.get(TimelineContentKey.TEXT.getCode()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeResolveContent(AgentTimeline row) throws Exception {
        AgentTimelineServiceImpl svc = new AgentTimelineServiceImpl(
                null, null, toolCallMapper, clarificationMapper, null, interactionMapper,
                compactRecordMapper, null, messageFeedbackService);
        Method m = AgentTimelineServiceImpl.class.getDeclaredMethod(
                "resolveContent", AgentTimeline.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(svc, row, Collections.emptyMap(), Collections.emptyMap());
    }
}