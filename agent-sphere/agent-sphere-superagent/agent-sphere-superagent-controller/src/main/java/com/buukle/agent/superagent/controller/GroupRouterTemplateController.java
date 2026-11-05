package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.dto.GroupRouterTemplateDTO;
import com.buukle.agent.superagent.service.GroupRouterTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/super-agents/group-router/template")
@WithTenant
@RequiredArgsConstructor
public class GroupRouterTemplateController extends BaseController {

    private final GroupRouterTemplateService templateService;

    @RequirePermission("super-agent:read")
    @GetMapping
    public ResponseEntity<?> get() {
        return ok(templateService.get());
    }

    @RequirePermission("super-agent:update")
    @PatchMapping
    public ResponseEntity<?> update(@Valid @RequestBody GroupRouterTemplateDTO dto) {
        return ok(templateService.update(dto.getTemplate()));
    }
}
