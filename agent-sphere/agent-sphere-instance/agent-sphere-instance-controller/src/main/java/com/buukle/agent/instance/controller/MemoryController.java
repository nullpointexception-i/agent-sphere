package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.service.MemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping("/api/v1/instance/memories")
@RequiredArgsConstructor
public class MemoryController extends BaseController {
    private final MemoryService memoryService;

    @GetMapping("/by-session/{sessionId}")
    public ResponseEntity<?> listBySession(@PathVariable Long sessionId) {
        return ok(memoryService.getMemoryBySession(sessionId));
    }

    @GetMapping("/by-run/{runId}")
    public ResponseEntity<?> listByRun(@PathVariable Long runId) {
        return ok(memoryService.getMemoryByRun(runId));
    }

    @GetMapping("/by-task/{taskId}")
    public ResponseEntity<?> listByTask(@PathVariable Long taskId) {
        return ok(memoryService.getMemoryByTask(taskId));
    }
}
