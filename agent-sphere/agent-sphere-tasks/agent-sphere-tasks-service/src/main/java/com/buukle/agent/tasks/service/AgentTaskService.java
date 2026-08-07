package com.buukle.agent.tasks.service;

import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;

public interface AgentTaskService {
    TaskVO submit(CreateTaskDTO dto);

    TaskVO get(Long id);

    void stop(Long id);
}
