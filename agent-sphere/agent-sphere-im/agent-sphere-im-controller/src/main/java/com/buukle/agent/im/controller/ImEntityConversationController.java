package com.buukle.agent.im.controller;

import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.dtvo.dto.EntityConversationDTO;
import com.buukle.agent.im.dtvo.vo.ConversationVO;
import com.buukle.agent.im.service.ImConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@WithTenant
@RequestMapping(ApiPrefix.V1 + "/im/conversations/entity")
@RequiredArgsConstructor
public class ImEntityConversationController extends BaseController {

    private final ImConversationService conversationService;

    @PostMapping
    public ResponseEntity<ConversationVO> ensure(@Valid @RequestBody EntityConversationDTO dto) {
        return ok(conversationService.ensureEntityConversation(dto));
    }

    @GetMapping
    public ResponseEntity<ConversationVO> find(@Valid EntityConversationDTO dto) {
        return ok(conversationService.findEntityConversation(dto));
    }
}
