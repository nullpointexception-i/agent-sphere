package com.buukle.agent.im.controller;

import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.service.ImNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping(ApiPrefix.V1 + "/im/notifications")
@WithTenant
@RequiredArgsConstructor
public class ImNotificationController extends BaseController {

    private final ImNotificationService notificationService;

    @GetMapping
    public ResponseEntity<?> page(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ok(notificationService.page(unreadOnly, page, pageSize));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount() {
        return ok(notificationService.unreadCount());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ok();
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> markAllRead() {
        notificationService.markAllRead();
        return ok();
    }
}
