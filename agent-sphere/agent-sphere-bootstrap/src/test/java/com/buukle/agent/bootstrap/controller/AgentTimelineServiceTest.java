package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.domain.AgentTimeline;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.enums.TimelineContentKey;
import com.buukle.agent.instance.dtvo.vo.AgentTimelineVO;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeResolveContent(AgentTimeline row) throws Exception {
        AgentTimelineServiceImpl svc = new AgentTimelineServiceImpl(null, null, toolCallMapper, null, null, null);
        Method m = AgentTimelineServiceImpl.class.getDeclaredMethod("resolveContent", AgentTimeline.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(svc, row, Collections.emptyMap());
    }
}