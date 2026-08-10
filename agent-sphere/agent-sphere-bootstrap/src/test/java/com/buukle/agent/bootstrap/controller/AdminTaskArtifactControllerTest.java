package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.exception.GlobalExceptionHandler;
import com.buukle.agent.tasks.controller.AdminTaskArtifactController;
import com.buukle.agent.tasks.dtvo.TaskArtifactVO;
import com.buukle.agent.tasks.service.AgentTaskService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminTaskArtifactControllerTest {

    MockMvc mockMvc;

    @Mock
    AgentTaskService taskService;

    @InjectMocks
    AdminTaskArtifactController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private TaskArtifactVO artifactVO(Long id) {
        TaskArtifactVO vo = new TaskArtifactVO();
        vo.setId(id);
        vo.setTaskId(7L);
        vo.setTaskGoal("整理月报");
        vo.setArtifactType("task_contract");
        vo.setSchemaRef("contract");
        vo.setRunId(21L);
        vo.setStatus("ACTIVE");
        vo.setContent("{\"summary\":\"done\"}");
        return vo;
    }

    @Test
    void list_shouldReturnPagedArtifacts() throws Exception {
        Page<TaskArtifactVO> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(artifactVO(3L)));
        given(taskService.pageArtifacts(anyString(), isNull(), anyInt(), anyInt())).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/task-artifacts")
                        .param("keyword", "contract")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value(3))
                .andExpect(jsonPath("$.records[0].taskId").value(7))
                .andExpect(jsonPath("$.records[0].taskGoal").value("整理月报"))
                .andExpect(jsonPath("$.records[0].artifactType").value("task_contract"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void detail_shouldReturnArtifact() throws Exception {
        given(taskService.getArtifact(eq(3L))).willReturn(artifactVO(3L));

        mockMvc.perform(get("/api/v1/admin/task-artifacts/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.content").value("{\"summary\":\"done\"}"));
    }
}
