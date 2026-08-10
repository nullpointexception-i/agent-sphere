package com.buukle.agent.tasks.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.sso.spi.CallerAuth;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskArtifactVO;
import com.buukle.agent.tasks.dtvo.TaskVO;

import java.time.LocalDateTime;

public interface AgentTaskService {
    TaskVO submit(CreateTaskDTO dto, CallerAuth auth);

    TaskVO get(Long id, Integer logOffset, Integer toolLogOffset, CallerAuth auth);

    void stop(Long id, CallerAuth auth);

    Page<TaskVO> page(String keyword, String status, LocalDateTime startTime, LocalDateTime endTime, int page, int size);

    /** 任务产物分页（归属按 created_by 租户过滤）。 */
    Page<TaskArtifactVO> pageArtifacts(String keyword, Long taskId, int page, int size);

    /** 任务产物详情。 */
    TaskArtifactVO getArtifact(Long id);
}
