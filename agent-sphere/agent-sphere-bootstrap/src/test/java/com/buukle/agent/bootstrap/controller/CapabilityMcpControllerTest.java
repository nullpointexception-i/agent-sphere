package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.mcp.controller.CapabilityMcpController;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CapabilityMcpControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    CapabilityMcpService capabilityMcpService;

    @InjectMocks
    CapabilityMcpController capabilityMcpController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(capabilityMcpController).build();
    }

    @Test
    void create_shouldReturn201() throws Exception {
        CreateMcpDTO dto = new CreateMcpDTO();
        dto.setName("My MCP Server");
        dto.setDescription("A test MCP server");
        dto.setServerUrl("http://localhost:9090");
        dto.setServerType("sse");
        McpVO vo = new McpVO();
        vo.setId(1L);
        vo.setName("My MCP Server");
        vo.setDescription("A test MCP server");
        vo.setServerUrl("http://localhost:9090");
        vo.setServerType("sse");
        vo.setStatus("ENABLED");
        vo.setCreatedAt("2026-05-30 10:00:00");
        given(capabilityMcpService.createMcp(any(CreateMcpDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/capability/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1L))
            .andExpect(jsonPath("$.name").value("My MCP Server"))
            .andExpect(jsonPath("$.serverUrl").value("http://localhost:9090"))
            .andExpect(jsonPath("$.serverType").value("sse"));
    }
}
