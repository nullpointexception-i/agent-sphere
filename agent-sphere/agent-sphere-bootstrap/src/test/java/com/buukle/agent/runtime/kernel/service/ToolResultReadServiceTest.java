package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolResultReadServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    AgentToolCallRecordSpi toolCallRecordSpi;

    @InjectMocks
    ToolResultReadService service;

    @Test
    void readsChunkBySessionAndCallId() throws Exception {
        AgentToolCallRecordVO record = new AgentToolCallRecordVO();
        record.setCallId("call-1");
        record.setToolName("builtin_5");
        record.setArtifact("x".repeat(500));
        when(toolCallRecordSpi.getBySessionAndCallId(9L, "call-1")).thenReturn(record);

        JsonNode out = JSON.readTree(service.read(9L,
                "{\"callId\":\"call-1\",\"offset\":100,\"maxChars\":200}"));

        assertEquals(200, out.path("content").asText().length());
        assertEquals(100, out.path("offset").asInt());
        assertEquals(300, out.path("nextOffset").asInt());
        assertTrue(out.path("truncated").asBoolean());
        assertEquals("builtin_5", out.path("toolName").asText());
    }

    @Test
    void returnsNotFoundForUnknownCallId() throws Exception {
        when(toolCallRecordSpi.getBySessionAndCallId(9L, "missing")).thenReturn(null);

        JsonNode out = JSON.readTree(service.read(9L, "{\"callId\":\"missing\"}"));

        assertEquals("artifact_not_found", out.path("error").asText());
        assertFalse(out.has("content"));
    }

    @Test
    void requiresCallId() throws Exception {
        JsonNode out = JSON.readTree(service.read(9L, "{}"));
        assertEquals("callId_required", out.path("error").asText());
    }
}
