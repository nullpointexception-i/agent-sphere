package com.buukle.agent.tasks.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;

import java.time.LocalDateTime;

public interface AgentTaskService {
    TaskVO submit(CreateTaskDTO dto);

    TaskVO get(Long id);

    void stop(Long id);

    Page<TaskVO> page(String keyword, String status, LocalDateTime startTime, LocalDateTime endTime, int page, int size);
}
