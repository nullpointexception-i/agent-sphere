package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping("/api/v1/instance/interactions")
@RequiredArgsConstructor
public class LlmInteractionController extends BaseController {

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;

    @GetMapping
    public ResponseEntity<?> listByRunId(@RequestParam Long runId,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "20") int limit) {
        return ok(interactionRecordSpi.listByRunId(runId, offset, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(interactionRecordSpi.getById(id));
    }
}
