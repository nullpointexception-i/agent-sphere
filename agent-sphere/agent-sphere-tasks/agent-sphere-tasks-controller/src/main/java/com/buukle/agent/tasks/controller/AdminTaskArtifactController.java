package com.buukle.agent.tasks.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.tasks.dtvo.TaskArtifactVO;
import com.buukle.agent.tasks.service.AgentTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务产物（task artifact）查询（RBAC：admin:tasks:read）。
 */
@WithTenant
@RestController
@RequestMapping("/api/v1/admin/task-artifacts")
@RequiredArgsConstructor
public class AdminTaskArtifactController extends BaseController {

    private final AgentTaskService taskService;

    @RequirePermission("admin:tasks:read")
    @GetMapping
    public ResponseEntity<Page<TaskArtifactVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(taskService.pageArtifacts(keyword, taskId, page, size));
    }

    @RequirePermission("admin:tasks:read")
    @GetMapping("/{id}")
    public ResponseEntity<TaskArtifactVO> detail(@PathVariable Long id) {
        return ok(taskService.getArtifact(id));
    }
}
