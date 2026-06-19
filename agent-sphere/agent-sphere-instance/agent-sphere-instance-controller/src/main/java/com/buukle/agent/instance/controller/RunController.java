package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.service.RunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping("/api/v1/instance/runs")
@RequiredArgsConstructor
public class RunController extends BaseController {
    private final RunService runService;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRunDTO dto) {
        return created(runService.createRun(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(runService.getRun(id));
    }

    @GetMapping
    public ResponseEntity<?> listBySession(@RequestParam Long sessionId,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return ok(runService.pageRunsBySession(sessionId, keyword, page, size));
    }
}
