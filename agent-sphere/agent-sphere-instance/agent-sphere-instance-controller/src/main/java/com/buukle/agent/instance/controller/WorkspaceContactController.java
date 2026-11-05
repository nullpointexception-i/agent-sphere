package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.spi.UserSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only user directory for the C-end workspace. */
@RestController
@RequestMapping("/api/v1/workspace/contacts")
@RequiredArgsConstructor
@WithTenant
public class WorkspaceContactController extends BaseController {

    private final UserSpi userSpi;

    @GetMapping("/users")
    public ResponseEntity<?> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ok(userSpi.pageContacts(page, size, keyword));
    }
}
