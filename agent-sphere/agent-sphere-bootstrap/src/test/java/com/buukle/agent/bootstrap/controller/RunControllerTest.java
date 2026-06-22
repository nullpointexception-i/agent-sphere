package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.controller.RunController;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.service.RunService;
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
class RunControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    RunService runService;

    @InjectMocks
    RunController runController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(runController).build();
    }

    @Test
    void create_shouldReturn201() throws Exception {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType("USER");
        dto.setUserMessage("你好，请帮我查一下订单");
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setSessionId(1L);
        vo.setType("USER");
        vo.setUserMessage("你好，请帮我查一下订单");
        vo.setStatus("PENDING");
        vo.setCreatedAt("2026-05-30 10:00:00");
        given(runService.createRun(any(CreateRunDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/instance/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.sessionId").value(1L))
                .andExpect(jsonPath("$.type").value("USER"))
                .andExpect(jsonPath("$.userMessage").value("你好，请帮我查一下订单"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
