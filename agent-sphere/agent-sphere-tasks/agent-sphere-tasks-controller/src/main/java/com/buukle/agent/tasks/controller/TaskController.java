package com.buukle.agent.tasks.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.sso.spi.CallerAuth;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标驱动多轮任务（外部开放接口，业务层以 code+subject+businessType 认证）。
 */
@RestController
@RequestMapping("/api/v1/api/tasks")
@RequiredArgsConstructor
public class TaskController extends BaseController {

    private final AgentTaskService taskService;

    @PostMapping
    public ResponseEntity<TaskVO> submit(@Valid @RequestBody CreateTaskDTO dto) {
        CallerAuth auth = CallerAuth.of(dto.getCode(), dto.getSubject(), dto.getBusinessType());
        return created(taskService.submit(dto, auth));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskVO> get(@PathVariable Long id,
                                      @RequestParam String code,
                                      @RequestParam String subject,
                                      @RequestParam String businessType) {
        return ok(taskService.get(id, CallerAuth.of(code, subject, businessType)));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Long id,
                                  @RequestParam String code,
                                  @RequestParam String subject,
                                  @RequestParam String businessType) {
        taskService.stop(id, CallerAuth.of(code, subject, businessType));
        return ok();
    }
}
