package com.buukle.agent.tasks.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;
import com.buukle.agent.tasks.service.AgentTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * tasks 能力管理（RBAC：admin:tasks:*）。
 */
@WithTenant
@RestController
@RequestMapping("/api/v1/admin/tasks")
@RequiredArgsConstructor
public class AdminTasksController extends BaseController {

    private final AgentTaskService taskService;

    @AuditLog(action = "CREATE", resourceType = "Task", resourceId = "#result?.body?.id")
    @RequirePermission("admin:tasks:create")
    @PostMapping
    public ResponseEntity<TaskVO> create(@Valid @RequestBody CreateTaskDTO dto) {
        return created(taskService.submit(dto));
    }

    @RequirePermission("admin:tasks:read")
    @GetMapping
    public ResponseEntity<Page<TaskVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(taskService.page(keyword, status, startTime, endTime, page, size));
    }

    @RequirePermission("admin:tasks:read")
    @GetMapping("/{id}")
    public ResponseEntity<TaskVO> detail(@PathVariable Long id) {
        return ok(taskService.get(id));
    }

    @AuditLog(action = "STOP_TASK", resourceType = "Task", resourceId = "#id")
    @RequirePermission("admin:tasks:update")
    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Long id) {
        taskService.stop(id);
        return ok();
    }
}
