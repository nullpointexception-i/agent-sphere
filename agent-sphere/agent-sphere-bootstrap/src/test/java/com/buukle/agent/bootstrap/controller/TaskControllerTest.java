package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.sso.spi.CallerAuth;
import com.buukle.agent.tasks.controller.TaskController;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;
import com.buukle.agent.tasks.service.AgentTaskService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AgentTaskService taskService;

    @InjectMocks
    TaskController taskController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(taskController).build();
    }

    @Test
    void submit_shouldReturn201() throws Exception {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("梳理 4 月订单");
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");
        TaskVO vo = new TaskVO();
        vo.setId(1L);
        vo.setStatus("RUNNING");
        given(taskService.submit(any(CreateTaskDTO.class), any(CallerAuth.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    void get_shouldReturnTask() throws Exception {
        TaskVO vo = new TaskVO();
        vo.setId(7L);
        vo.setStatus("COMPLETED");
        given(taskService.get(eq(7L), any(CallerAuth.class))).willReturn(vo);

        mockMvc.perform(get("/api/v1/api/tasks/7")
                        .param("code", "bole")
                        .param("subject", "elvin")
                        .param("businessType", "sourcing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7L));
    }

    @Test
    void stop_shouldReturnOk() throws Exception {
        willDoNothing().given(taskService).stop(eq(7L), any(CallerAuth.class));

        mockMvc.perform(post("/api/v1/api/tasks/7/stop")
                        .param("code", "bole")
                        .param("subject", "elvin")
                        .param("businessType", "sourcing"))
                .andExpect(status().isOk());
    }
}
