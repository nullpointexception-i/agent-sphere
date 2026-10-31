package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.service.util.ToolMetaResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolMetaResolverTest {

    @Test
    void buildsWebMetaWithDurationAndBodyChars() {
        AgentToolCallRecord record = record("builtin_2",
                "{\"url\":\"https://example.com/a\"}",
                "{\"title\":\"Example\",\"markdown\":\"hello\",\"statusCode\":200}");
        record.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        record.setUpdatedAt(record.getCreatedAt().plusNanos(1_200_000_000L));

        Map<String, Object> meta = ToolMetaResolver.build(record, null);

        assertEquals(1200L, meta.get("durationMs"));
        assertEquals("https://example.com/a", meta.get("url"));
        assertEquals("example.com", meta.get("host"));
        assertEquals("Example", meta.get("title"));
        assertEquals(200, meta.get("statusCode"));
        assertEquals(5, meta.get("chars"));
    }

    @Test
    void buildsMcpServerName() {
        CapabilityMcpSpi mcpSpi = mock(CapabilityMcpSpi.class);
        McpVO mcp = new McpVO();
        mcp.setId(7L);
        mcp.setName("Search Server");
        when(mcpSpi.getMcp(7L)).thenReturn(mcp);

        Map<String, Object> meta = ToolMetaResolver.build(
                record("mcp_7_0", "{}", "{\"content\":[]}"), mcpSpi);

        assertEquals(7L, meta.get("serverId"));
        assertEquals("Search Server", meta.get("server"));
    }

    @Test
    void buildsDocumentVersionAndChars() {
        Map<String, Object> meta = ToolMetaResolver.build(record("builtin_6", "{}",
                "{\"documentId\":9,\"version\":3,\"title\":\"Doc\",\"content\":\"abc\",\"totalLines\":1}"), null);

        assertEquals(9L, meta.get("documentId"));
        assertEquals(3L, meta.get("version"));
        assertEquals(3, meta.get("chars"));
        assertEquals(1L, meta.get("totalLines"));
    }

    @Test
    void buildsCliExitCodeAndFirstLine() {
        Map<String, Object> meta = ToolMetaResolver.build(record("cli_1", "{}",
                "{\"exitCode\":0,\"stdout\":\"line1\\nline2\"}"), null);

        assertEquals(0, meta.get("exitCode"));
        assertEquals("line1", meta.get("firstLine"));
        assertEquals(11, meta.get("stdoutChars"));
    }

    private AgentToolCallRecord record(String toolName, String args, String artifact) {
        AgentToolCallRecord record = new AgentToolCallRecord();
        record.setToolName(toolName);
        record.setArgumentsJson(args);
        record.setArtifact(artifact);
        return record;
    }
}
