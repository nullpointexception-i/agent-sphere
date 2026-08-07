package com.buukle.agent.tasks.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;
import com.buukle.agent.tasks.service.AgentTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标驱动多轮任务（Bearer 鉴权）。
 */
@RestController
@RequestMapping("/api/v1/api/tasks")
@RequiredArgsConstructor
public class TaskController extends BaseController {

    private final AgentTaskService taskService;

    @PostMapping
    public ResponseEntity<TaskVO> submit(@Valid @RequestBody CreateTaskDTO dto) {
        return created(taskService.submit(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskVO> get(@PathVariable Long id) {
        return ok(taskService.get(id));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Long id) {
        taskService.stop(id);
        return ok();
    }
}
