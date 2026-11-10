package com.buukle.agent.instance.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.MessageFeedbackDTO;
import com.buukle.agent.instance.service.MessageFeedbackService;
import com.buukle.agent.instance.service.support.SessionAccessGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping(ApiPrefix.V1 + "/instance/sessions")
@RequiredArgsConstructor
@WithTenant
public class MessageFeedbackController extends BaseController {

    private final MessageFeedbackService feedbackService;
    private final SessionAccessGuard sessionAccessGuard;

    @PostMapping("/{sessionId}/messages/{interactionId}/feedback")
    public ResponseEntity<?> setFeedback(@PathVariable Long sessionId,
                                         @PathVariable Long interactionId,
                                         @RequestParam(required = false) Long runId,
                                         @Valid @RequestBody MessageFeedbackDTO dto) {
        sessionAccessGuard.requireWritable(sessionId);
        String value = feedbackService.setFeedback(
                sessionId, runId, interactionId, AuthContext.getUserId(), dto.getValue());
        return ok(Map.of("value", value == null ? "" : value));
    }
}
