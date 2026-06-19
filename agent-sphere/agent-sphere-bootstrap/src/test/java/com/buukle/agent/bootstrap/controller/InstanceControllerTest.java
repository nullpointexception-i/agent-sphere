package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.controller.InstanceController;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.service.InstanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InstanceControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    InstanceService instanceService;

    @InjectMocks
    InstanceController instanceController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(instanceController).build();
    }

    @Test
    void get_shouldReturn200() throws Exception {
        InstanceVO vo = new InstanceVO();
        vo.setId(1L);
        vo.setName("Customer Support Agent");
        vo.setDescription("Handles customer inquiries");
        vo.setStatus("ENABLED");
        vo.setCreatedAt("2026-05-30 10:00:00");
        given(instanceService.getInstance(1L)).willReturn(vo);

        mockMvc.perform(get("/api/v1/instance/instances/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1L))
            .andExpect(jsonPath("$.name").value("Customer Support Agent"))
            .andExpect(jsonPath("$.status").value("ENABLED"));
    }
}
