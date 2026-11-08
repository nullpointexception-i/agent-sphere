package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.dto.ResolveApprovalDTO;
import com.buukle.agent.superagent.service.SuperAgentApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents/approvals")
@WithTenant
@RequiredArgsConstructor
public class SuperAgentApprovalController extends BaseController {

    private final SuperAgentApprovalService approvalService;

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/pending")
    public ResponseEntity<?> pending() {
        return ok(approvalService.listPending());
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{approvalId}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String approvalId,
                                     @Valid @RequestBody ResolveApprovalDTO dto) {
        approvalService.resolve(approvalId, dto);
        return ok();
    }
}
