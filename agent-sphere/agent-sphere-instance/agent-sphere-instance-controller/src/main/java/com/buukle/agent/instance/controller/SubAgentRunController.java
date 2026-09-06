package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.dtvo.vo.SubAgentTimelineItemVO;
import com.buukle.agent.instance.spi.AgentSubAgentRunSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instance")
@RequiredArgsConstructor
@WithTenant
public class SubAgentRunController extends BaseController {

    private final AgentSubAgentRunSpi subAgentRunSpi;

    @GetMapping("/sessions/{sessionId}/sub-agent-runs")
    public ResponseEntity<List<AgentSubAgentRunVO>> listBySession(@PathVariable Long sessionId) {
        return ok(subAgentRunSpi.listBySession(sessionId));
    }

    @GetMapping("/sub-agent-runs/{id}/timeline")
    public ResponseEntity<List<SubAgentTimelineItemVO>> timeline(@PathVariable Long id) {
        return ok(subAgentRunSpi.timeline(id));
    }
}