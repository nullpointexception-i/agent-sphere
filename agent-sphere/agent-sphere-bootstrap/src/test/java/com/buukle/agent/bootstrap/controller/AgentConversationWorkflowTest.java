package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.mcp.controller.CapabilityMcpController;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.buukle.agent.instance.controller.InstanceController;
import com.buukle.agent.instance.controller.SessionController;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.service.InstanceService;
import com.buukle.agent.instance.service.SessionService;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.model.controller.RouteController;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.service.RouteService;
import com.buukle.agent.runtime.orchestration.controller.ChatRuntimeController;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentConversationWorkflowTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    RouteService routeService;
    @Mock
    CapabilityMcpService capabilityMcpService;
    @Mock
    InstanceService instanceService;
    @Mock
    SessionService sessionService;
    @Mock
    ChatRuntimeService chatRuntimeService;
    @Mock
    RunSpi runSpi;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RouteController(routeService),
                new CapabilityMcpController(capabilityMcpService),
                new InstanceController(instanceService),
                new SessionController(sessionService, runSpi),
                new ChatRuntimeController(chatRuntimeService)
        ).build();
    }

    @Test
    void shouldCreateModelMcpAgentInstanceOpenSessionAndChat() throws Exception {
        given(routeService.createRoute(any(CreateRouteDTO.class))).willReturn(modelRoute());
        given(capabilityMcpService.createMcp(any(CreateMcpDTO.class))).willReturn(mcp());
        given(instanceService.createInstance(any(CreateInstanceDTO.class))).willReturn(instance());
        given(sessionService.createSession(any(CreateSessionDTO.class))).willReturn(session());
        given(chatRuntimeService.chat(eq(401L), any(SendMessageDTO.class))).willReturn(chatResponse());

        Long modelRouteId = postAndReadId("/api/v1/model/routes", createRouteRequest());

        mockMvc.perform(post("/api/v1/capability/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createMcpRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(201L))
                .andExpect(jsonPath("$.serverType").value("sse"));

        Long agentInstanceId = postAndReadId("/api/v1/instance/instances", createInstanceRequest(modelRouteId));
        Long sessionId = postAndReadId("/api/v1/instance/sessions", createSessionRequest(agentInstanceId));

        mockMvc.perform(post("/api/v1/runtime/{sessionId}/chat", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendMessageRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(501L))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        ArgumentCaptor<CreateInstanceDTO> instanceCaptor = ArgumentCaptor.forClass(CreateInstanceDTO.class);
        verify(instanceService).createInstance(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getModelRouteId()).isEqualTo(101L);
        assertThat(instanceCaptor.getValue().getName()).isEqualTo("Support Agent");

        ArgumentCaptor<SendMessageDTO> messageCaptor = ArgumentCaptor.forClass(SendMessageDTO.class);
        verify(chatRuntimeService).chat(eq(401L), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessage()).isEqualTo("你好，请总结今天的工单");
    }

    private Long postAndReadId(String url, Object request) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private CreateRouteDTO createRouteRequest() {
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(1L);
        dto.setModelName("gpt-4.1-mini");
        dto.setWeight(100);
        return dto;
    }

    private ModelRouteVO modelRoute() {
        ModelRouteVO vo = new ModelRouteVO();
        vo.setId(101L);
        vo.setProviderId(1L);
        vo.setModelName("gpt-4.1-mini");
        vo.setWeight(100);
        vo.setStatus("ACTIVE");
        return vo;
    }

    private CreateMcpDTO createMcpRequest() {
        CreateMcpDTO dto = new CreateMcpDTO();
        dto.setName("Ticket MCP");
        dto.setDescription("Ticket lookup tools");
        dto.setServerUrl("http://localhost:9090/sse");
        dto.setServerType("sse");
        return dto;
    }

    private McpVO mcp() {
        McpVO vo = new McpVO();
        vo.setId(201L);
        vo.setName("Ticket MCP");
        vo.setDescription("Ticket lookup tools");
        vo.setServerUrl("http://localhost:9090/sse");
        vo.setServerType("sse");
        vo.setStatus("ENABLED");
        return vo;
    }

    private CreateInstanceDTO createInstanceRequest(Long modelRouteId) {
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName("Support Agent");
        dto.setDescription("Handles support conversations");
        dto.setSystemPrompt("You are a support assistant.");
        dto.setModelRouteId(modelRouteId);
        dto.setCustomInstructions("{\"tone\":\"concise\"}");
        return dto;
    }

    private InstanceVO instance() {
        InstanceVO vo = new InstanceVO();
        vo.setId(301L);
        vo.setName("Support Agent");
        vo.setDescription("Handles support conversations");
        vo.setSystemPrompt("You are a support assistant.");
        vo.setModelRouteId(101L);
        vo.setCustomInstructions("{\"tone\":\"concise\"}");
        vo.setStatus("ENABLED");
        return vo;
    }

    private CreateSessionDTO createSessionRequest(Long agentInstanceId) {
        CreateSessionDTO dto = new CreateSessionDTO();
        dto.setAgentInstanceId(agentInstanceId);
        dto.setTitle("Daily support sync");
        return dto;
    }

    private SessionVO session() {
        SessionVO vo = new SessionVO();
        vo.setId(401L);
        vo.setAgentInstanceId(301L);
        vo.setTitle("Daily support sync");
        vo.setStatus("ACTIVE");
        return vo;
    }

    private SendMessageDTO sendMessageRequest() {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setMessage("你好，请总结今天的工单");
        return dto;
    }

    private ChatMessageResponseVO chatResponse() {
        ChatMessageResponseVO vo = new ChatMessageResponseVO();
        vo.setRunId(501L);
        vo.setStatus("PROCESSING");
        return vo;
    }
}
