package com.buukle.agent.superagent.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.service.WorkItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents/{superAgentId}/work-items")
@WithTenant
@RequiredArgsConstructor
public class WorkItemController extends BaseController {

    private final WorkItemService workItemService;

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long superAgentId,
                                  @RequestParam(required = false) String status) {
        return ok(workItemService.list(superAgentId, status));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long superAgentId, @PathVariable Long id) {
        return ok(workItemService.get(id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long superAgentId, @PathVariable Long id) {
        return ok(workItemService.retry(superAgentId, id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long superAgentId, @PathVariable Long id) {
        return ok(workItemService.cancel(superAgentId, id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long superAgentId, @PathVariable Long id) {
        return ok(workItemService.confirmSpeculation(superAgentId, id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/{id}/timeline")
    public ResponseEntity<?> timeline(@PathVariable Long superAgentId,
                                      @PathVariable Long id,
                                      @RequestParam(required = false) Long beforeSeq,
                                      @RequestParam(required = false) Long afterSeq,
                                      @RequestParam(defaultValue = "50") int limit) {
        return ok(workItemService.workerTimeline(superAgentId, id, beforeSeq, afterSeq, limit));
    }
}
