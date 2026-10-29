package com.buukle.agent.runtime.orchestration.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.instance.dtvo.dto.ClarifyDTO;
import com.buukle.agent.instance.dtvo.dto.ResolveClarificationsDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.dtvo.dto.ResolveToolApprovalDTO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/runtime")
@WithTenant
@RequiredArgsConstructor
public class ChatRuntimeController {

    private final ChatRuntimeService chatRuntimeService;

    @PostMapping("/{sessionId}/chat")
    public ResponseEntity<ChatMessageResponseVO> chat(@PathVariable Long sessionId,
                                                       @Valid @RequestBody SendMessageDTO dto) {
        ChatMessageResponseVO response = chatRuntimeService.chat(sessionId, dto);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<?> stopSession(@PathVariable Long sessionId) {
        chatRuntimeService.stopSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sessionId}/run/{runId}/stop")
    public ResponseEntity<?> stopRun(@PathVariable Long sessionId,
                                      @PathVariable Long runId) {
        chatRuntimeService.stopRun(sessionId, runId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{sessionId}/tool-approvals/pending")
    public ResponseEntity<?> pendingToolApproval(@PathVariable Long sessionId) {
        return ResponseEntity.ok(chatRuntimeService.pendingToolApproval(sessionId));
    }

    @PostMapping("/{sessionId}/run/{runId}/tool-approvals/{approvalId}")
    public ResponseEntity<?> resolveToolApproval(@PathVariable Long sessionId,
                                                 @PathVariable Long runId,
                                                 @PathVariable String approvalId,
                                                 @RequestBody ResolveToolApprovalDTO dto) {
        chatRuntimeService.resolveToolApproval(sessionId, runId, approvalId,
                com.buukle.agent.util.json.JsonUtils.toJson(dto.getDecisions()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sessionId}/run/{runId}/clarify")
    public ResponseEntity<?> clarify(@PathVariable Long sessionId,
                                      @PathVariable Long runId,
                                      @Valid @RequestBody ClarifyDTO dto) {
        ChatMessageResponseVO result = chatRuntimeService.resumeFromClarification(sessionId, runId, dto.getResponse(), dto.getClarificationId());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{sessionId}/clarifications/pending")
    public ResponseEntity<?> pendingClarifications(@PathVariable Long sessionId) {
        return ResponseEntity.ok(chatRuntimeService.pendingClarifications(sessionId));
    }

    @PostMapping("/{sessionId}/run/{runId}/clarifications/resolve")
    public ResponseEntity<?> resolveClarifications(@PathVariable Long sessionId,
                                                    @PathVariable Long runId,
                                                    @Valid @RequestBody ResolveClarificationsDTO dto) {
        return ResponseEntity.ok(chatRuntimeService.resolveClarifications(sessionId, runId, dto));
    }
}
