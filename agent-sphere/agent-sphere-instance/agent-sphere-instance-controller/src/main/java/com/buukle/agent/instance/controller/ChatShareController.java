package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.CreateChatShareDTO;
import com.buukle.agent.instance.service.ChatShareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/instance/sessions")
@RequiredArgsConstructor
@WithTenant
public class ChatShareController extends BaseController {

    private final ChatShareService chatShareService;

    @PostMapping("/{sessionId}/share")
    public ResponseEntity<?> share(@PathVariable Long sessionId,
                                   @Valid @RequestBody CreateChatShareDTO dto) {
        return ok(chatShareService.create(sessionId, dto.getRunId(), dto.getRunCount(), AuthContext.getUsername()));
    }
}
