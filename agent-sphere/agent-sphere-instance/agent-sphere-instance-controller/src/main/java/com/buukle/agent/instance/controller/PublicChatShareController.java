package com.buukle.agent.instance.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.service.ChatShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/shares")
@RequiredArgsConstructor
public class PublicChatShareController extends BaseController {

    private final ChatShareService chatShareService;

    @GetMapping("/{token}")
    public ResponseEntity<?> get(@PathVariable String token) {
        return ok(chatShareService.getShared(token));
    }
}
