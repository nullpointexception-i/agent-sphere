package com.buukle.agent.instance.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WithTenant
@RestController
@RequestMapping(ApiPrefix.V1 + "/instance/sessions")
@RequiredArgsConstructor
public class SessionTimelineController extends BaseController {

    private final AgentTimelineSpi agentTimelineSpi;
    private final AgentLlmInteractionRecordSpi interactionRecordSpi;

    /** Unified flattened Timeline list: keyset pagination (beforeSeq scrolls back / afterSeq catches up after disconnects / empty = latest page). */
    @GetMapping("/{sessionId}/timeline")
    public ResponseEntity<?> timeline(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(defaultValue = "5") int limit) {
        return ok(agentTimelineSpi.page(sessionId, beforeSeq, afterSeq, limit));
    }

    /** Session-level usage aggregation (bottom-docked display in the chat area; refreshed once a run reaches a terminal state). */
    @GetMapping("/{sessionId}/usage")
    public ResponseEntity<?> usage(@PathVariable Long sessionId) {
        return ok(interactionRecordSpi.usageSummary(sessionId));
    }
}