package com.buukle.agent.runtime.orchestration.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.instance.dtvo.dto.ClarifyDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
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

    @PostMapping("/{sessionId}/run/{runId}/stop")
    public ResponseEntity<?> stopRun(@PathVariable Long sessionId,
                                     @PathVariable Long runId) {
        SessionRunner.cancelRun(runId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sessionId}/run/{runId}/clarify")
    public ResponseEntity<?> clarify(@PathVariable Long sessionId,
                                      @PathVariable Long runId,
                                      @Valid @RequestBody ClarifyDTO dto) {
        ChatMessageResponseVO result = chatRuntimeService.resumeFromClarification(sessionId, runId, dto.getResponse(), dto.getClarificationId());
        return ResponseEntity.ok(result);
    }
}
