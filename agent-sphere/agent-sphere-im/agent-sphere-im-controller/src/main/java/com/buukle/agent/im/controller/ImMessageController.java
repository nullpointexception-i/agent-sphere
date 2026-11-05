package com.buukle.agent.im.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.dtvo.dto.SendMessageDTO;
import com.buukle.agent.im.service.ImMessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/im/conversations/{conversationId}/messages")
@WithTenant
@RequiredArgsConstructor
public class ImMessageController extends BaseController {

    private final ImMessageService messageService;

    @RequirePermission("im:conversation:read")
    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long conversationId,
                                  @RequestParam(required = false) Long beforeSeq,
                                  @RequestParam(required = false) Long afterSeq,
                                  @RequestParam(defaultValue = "30") int limit) {
        return ok(messageService.list(conversationId, beforeSeq, afterSeq, limit));
    }

    @RequirePermission("im:conversation:read")
    @GetMapping("/page")
    public ResponseEntity<?> page(@PathVariable Long conversationId,
                                  @RequestParam(required = false) Long beforeSeq,
                                  @RequestParam(required = false) Long afterSeq,
                                  @RequestParam(defaultValue = "30") int limit) {
        return ok(messageService.page(conversationId, beforeSeq, afterSeq, limit));
    }

    @RequirePermission("im:conversation:read")
    @GetMapping("/search")
    public ResponseEntity<?> search(@PathVariable Long conversationId,
                                    @RequestParam String keyword,
                                    @RequestParam(required = false) Long beforeSeq,
                                    @RequestParam(defaultValue = "30") int limit) {
        return ok(messageService.search(conversationId, keyword, beforeSeq, limit));
    }

    @RequirePermission("im:message:send")
    @PostMapping
    public ResponseEntity<?> send(@PathVariable Long conversationId,
                                  @Valid @RequestBody SendMessageDTO dto) {
        return created(messageService.send(conversationId, dto));
    }

    @RequirePermission("im:message:send")
    @PutMapping("/{messageId}")
    public ResponseEntity<?> edit(@PathVariable Long conversationId,
                                  @PathVariable Long messageId,
                                  @RequestBody Object content) {
        return ok(messageService.edit(conversationId, messageId, content));
    }

    @RequirePermission("im:message:send")
    @DeleteMapping("/{messageId}")
    public ResponseEntity<?> recall(@PathVariable Long conversationId,
                                    @PathVariable Long messageId) {
        messageService.recall(conversationId, messageId);
        return deleted();
    }
}
