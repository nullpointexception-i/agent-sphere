package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.tasks.controller.AdminTasksController;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminTasksControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AgentTaskService taskService;

    @InjectMocks
    AdminTasksController adminTasksController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminTasksController).build();
    }

    @Test
    void list_shouldReturnPage() throws Exception {
        TaskVO vo = new TaskVO();
        vo.setId(1L);
        vo.setGoal("寻访任务");
        vo.setStatus("RUNNING");
        Page<TaskVO> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(vo));
        given(taskService.page(anyString(), anyString(),
                any(), any(), anyInt(), anyInt())).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/tasks")
                        .param("keyword", "寻访")
                        .param("status", "RUNNING")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value(1L))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void detail_shouldReturnTask() throws Exception {
        TaskVO vo = new TaskVO();
        vo.setId(7L);
        vo.setGoal("整理月报");
        vo.setStatus("COMPLETED");
        given(taskService.get(7L)).willReturn(vo);

        mockMvc.perform(get("/api/v1/admin/tasks/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void create_shouldReturn201() throws Exception {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("梳理 4 月订单");
        TaskVO vo = new TaskVO();
        vo.setId(1L);
        vo.setStatus("RUNNING");
        given(taskService.submit(any(CreateTaskDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/admin/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    void stop_shouldReturnOk() throws Exception {
        willDoNothing().given(taskService).stop(anyLong());

        mockMvc.perform(post("/api/v1/admin/tasks/7/stop"))
                .andExpect(status().isOk());
    }
}
