package com.buukle.agent.agui.controller;

import com.buukle.agent.agui.dtvo.AguiRunInputVO;
import com.buukle.agent.agui.dtvo.CopilotAgentDefinitionVO;
import com.buukle.agent.agui.service.CopilotRuntimeService;
import com.buukle.agent.common.context.WithTenant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/copilot")
@WithTenant
@RequiredArgsConstructor
public class CopilotRuntimeController {

    private final CopilotRuntimeService copilotRuntimeService;

    @GetMapping("/agent/{agentId}/info")
    public ResponseEntity<CopilotAgentDefinitionVO> info(@PathVariable Long agentId) {
        return ResponseEntity.ok(copilotRuntimeService.getAgentDefinition(agentId));
    }

    @PostMapping("/agent/{agentId}/services/chat/run")
    public SseEmitter run(@PathVariable Long agentId, @RequestBody AguiRunInputVO input) {
        return copilotRuntimeService.run(agentId, input);
    }

    @PostMapping("/agent/{agentId}/services/chat/connect")
    public SseEmitter connect(@PathVariable Long agentId, @RequestParam Long threadId) {
        return copilotRuntimeService.connect(agentId, threadId);
    }

    @PostMapping("/agent/{agentId}/{threadId}/stop")
    public ResponseEntity<Void> stop(@PathVariable Long agentId,
                                     @PathVariable Long threadId,
                                     @RequestParam(required = false) Long runId) {
        copilotRuntimeService.stop(agentId, threadId, runId);
        return ResponseEntity.ok().build();
    }
}
