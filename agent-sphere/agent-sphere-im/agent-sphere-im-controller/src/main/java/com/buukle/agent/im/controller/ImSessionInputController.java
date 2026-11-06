package com.buukle.agent.im.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.dtvo.dto.ResolveSessionInputDTO;
import com.buukle.agent.im.service.ImSessionInputService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User decisions for Router parked inputs surfaced in the DM:
 * L2 tool approval cards are answered through this endpoint; clarifications
 * accept plain text and never need this route.
 */
@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/im/conversations/{conversationId}/session-inputs")
@WithTenant
@RequiredArgsConstructor
public class ImSessionInputController extends BaseController {

    private final ImSessionInputService sessionInputService;

    @RequirePermission("im:message:send")
    @PostMapping("/{inputId}/approve")
    public ResponseEntity<?> approve(@PathVariable Long conversationId,
                                     @PathVariable String inputId,
                                     @Valid @RequestBody ResolveSessionInputDTO dto) {
        sessionInputService.resolveApproval(conversationId, inputId, dto);
        return ok();
    }
}
