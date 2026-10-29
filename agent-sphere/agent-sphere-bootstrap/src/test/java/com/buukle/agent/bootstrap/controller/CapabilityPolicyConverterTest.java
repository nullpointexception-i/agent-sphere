package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.cli.domain.CapabilityCli;
import com.buukle.agent.capability.cli.dtvo.dto.CreateCliDTO;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import com.buukle.agent.capability.cli.service.converter.CapabilityCliConverter;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityPolicyConverterTest {

    @Test
    void mcpPolicyRoundTrips() {
        CreateMcpDTO dto = new CreateMcpDTO();
        dto.setName("m");
        dto.setServerUrl("https://example.com");
        dto.setServerType("http");
        dto.setCompressEnabled(true);
        dto.setAccessMode("READ");
        dto.setSecurityLevel("L2");
        dto.setApprovalScope("RUN");
        CapabilityMcpConverter converter = new CapabilityMcpConverter();
        CapabilityMcp entity = converter.toDO(dto);
        McpVO vo = converter.toVO(entity);
        assertTrue(vo.getCompressEnabled());
        assertEquals("READ", vo.getAccessMode());
        assertEquals("L2", vo.getSecurityLevel());
        assertEquals("RUN", vo.getApprovalScope());
    }

    @Test
    void cliPolicyRoundTrips() {
        CreateCliDTO dto = new CreateCliDTO();
        dto.setName("c");
        dto.setCommandTemplate("echo");
        dto.setWorkingDir("/tmp");
        dto.setCompressEnabled(true);
        dto.setAccessMode("WRITE");
        dto.setSecurityLevel("L2");
        dto.setApprovalScope("SESSION");
        CapabilityCliConverter converter = new CapabilityCliConverter();
        CapabilityCli entity = converter.toDO(dto);
        CliVO vo = converter.toVO(entity);
        assertTrue(vo.getCompressEnabled());
        assertEquals("WRITE", vo.getAccessMode());
        assertEquals("L2", vo.getSecurityLevel());
        assertEquals("SESSION", vo.getApprovalScope());
    }
}
