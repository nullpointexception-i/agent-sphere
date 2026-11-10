package com.buukle.agent.im.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.dtvo.dto.AnswerClarificationDTO;
import com.buukle.agent.im.service.ImClarificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Card-only Router clarification answers. Ordinary chat text never reaches this endpoint. */
@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping(ApiPrefix.V1 + "/im/conversations")
@WithTenant
@RequiredArgsConstructor
public class ImClarificationController extends BaseController {

    private final ImClarificationService clarificationService;

    @RequirePermission(PermissionCodes.IM_MESSAGE_SEND)
    @PostMapping("/{id}/clarifications/{inputId}/answer")
    public ResponseEntity<?> answer(@PathVariable Long id,
                                    @PathVariable String inputId,
                                    @RequestBody AnswerClarificationDTO dto) {
        return ok(clarificationService.answer(id, inputId, dto));
    }
}
