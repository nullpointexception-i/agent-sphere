package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.controller.SessionController;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.service.SessionService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    SessionService sessionService;

    @InjectMocks
    SessionController sessionController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sessionController).build();
    }

    @Test
    void create_shouldReturn201() throws Exception {
        CreateSessionDTO dto = new CreateSessionDTO();
        dto.setAgentInstanceId(1L);
        dto.setTitle("Test Session");
        SessionVO vo = new SessionVO();
        vo.setId(1L);
        vo.setTitle("Test Session");
        vo.setAgentInstanceId(1L);
        vo.setStatus("ACTIVE");
        vo.setCreatedAt("2026-05-30 10:00:00");
        given(sessionService.createSession(any(CreateSessionDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/instance/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("Test Session"))
                .andExpect(jsonPath("$.agentInstanceId").value(1L))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
