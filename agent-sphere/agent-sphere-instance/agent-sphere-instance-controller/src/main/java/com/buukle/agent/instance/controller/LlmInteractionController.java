package com.buukle.agent.instance.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.service.support.SessionAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping(ApiPrefix.V1 + "/instance/interactions")
@RequiredArgsConstructor
public class LlmInteractionController extends BaseController {

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;
    private final SessionAccessGuard sessionAccessGuard;

    @GetMapping
    public ResponseEntity<?> listByRunId(@RequestParam Long runId,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "20") int limit) {
        var run = interactionRecordSpi.listByRunId(runId, 0, 1);
        if (!run.isEmpty()) {
            sessionAccessGuard.requireReadable(run.get(0).getSessionId());
        }
        return ok(interactionRecordSpi.listByRunId(runId, offset, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        var record = interactionRecordSpi.getById(id);
        sessionAccessGuard.requireReadable(record.getSessionId());
        return ok(record);
    }
}
