package com.buukle.agent.bootstrap.controller;

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
        dto.setGoal("梳理 4 月订单并给出摘要");
        TaskVO vo = new TaskVO();
        vo.setId(1L);
        vo.setStatus("RUNNING");
        vo.setInstanceId(2L);
        vo.setSessionId(3L);
        vo.setRunId(4L);
        given(taskService.submit(any(CreateTaskDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.runId").value(4L));
    }

    @Test
    void get_shouldReturnTask() throws Exception {
        TaskVO vo = new TaskVO();
        vo.setId(7L);
        vo.setStatus("COMPLETED");
        vo.setResultJson("{\"reply\":\"done\"}");
        given(taskService.get(7L)).willReturn(vo);

        mockMvc.perform(get("/api/v1/api/tasks/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void stop_shouldReturnOk() throws Exception {
        willDoNothing().given(taskService).stop(eq(7L));

        mockMvc.perform(post("/api/v1/api/tasks/7/stop"))
                .andExpect(status().isOk());
    }
}
