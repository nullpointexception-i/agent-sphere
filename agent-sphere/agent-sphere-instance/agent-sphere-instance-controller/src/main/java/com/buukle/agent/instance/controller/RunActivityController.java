package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.service.RunActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping("/api/v1/instance/runs")
@RequiredArgsConstructor
public class RunActivityController extends BaseController {

    private final RunActivityService runActivityService;

    @GetMapping("/{runId}/activities")
    public ResponseEntity<?> listByRun(
            @PathVariable Long runId,
            @RequestParam Long sessionId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return ok(runActivityService.listByRun(runId, sessionId, offset, limit));
    }
}
