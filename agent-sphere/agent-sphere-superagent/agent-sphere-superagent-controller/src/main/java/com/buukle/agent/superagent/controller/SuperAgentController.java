package com.buukle.agent.superagent.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.dto.CreateSuperAgentDTO;
import com.buukle.agent.superagent.dtvo.dto.UpdateVisibilityDTO;
import com.buukle.agent.superagent.service.SuperAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents")
@WithTenant
@RequiredArgsConstructor
public class SuperAgentController extends BaseController {

    private final SuperAgentService superAgentService;

    @AuditLog(action = "CREATE", resourceType = "SuperAgent", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateSuperAgentDTO dto) {
        return created(superAgentService.create(dto));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Integer limit) {
        return ok(superAgentService.list(limit == null ? 0 : limit));
    }

    /** Public avatar picker for group creation; never exposes prompts or instance ids. */
    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/public")
    public ResponseEntity<?> listPublic(
            @RequestParam(required = false) List<Long> ownerUserIds) {
        return ok(superAgentService.listPublic(ownerUserIds));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(superAgentService.get(id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping("/{id}/instances")
    public ResponseEntity<?> instances(@PathVariable Long id) {
        return ok(superAgentService.getInstances(id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @Valid @RequestBody CreateSuperAgentDTO dto) {
        return ok(superAgentService.update(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "SuperAgent", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        superAgentService.delete(id);
        return deleted();
    }

    @AuditLog(action = "UPDATE", resourceType = "SuperAgent", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PatchMapping("/{id}/visibility")
    public ResponseEntity<?> updateVisibility(@PathVariable Long id,
                                              @RequestBody UpdateVisibilityDTO dto) {
        return ok(superAgentService.updateVisibility(id, dto.isPublicVisible()));
    }

    @AuditLog(action = "PAUSE", resourceType = "SuperAgent", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable Long id) {
        return ok(superAgentService.pause(id));
    }

    @AuditLog(action = "RESUME", resourceType = "SuperAgent", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        return ok(superAgentService.resume(id));
    }
}
