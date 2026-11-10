package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.dto.CreateLocalRuntimeDTO;
import com.buukle.agent.superagent.dtvo.dto.UpdateLocalRuntimeDTO;
import com.buukle.agent.superagent.service.LocalRuntimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console management of desktop local runtimes. */
@RestController
@RequestMapping(ApiPrefix.V1 + "/local-runtimes")
@WithTenant
@RequiredArgsConstructor
public class LocalRuntimeController extends BaseController {

    private final LocalRuntimeService localRuntimeService;

    @AuditLog(action = "CREATE", resourceType = "LocalRuntime",
            resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateLocalRuntimeDTO dto) {
        return created(localRuntimeService.create(dto));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping
    public ResponseEntity<?> list() {
        return ok(localRuntimeService.list());
    }

    @AuditLog(action = "UPDATE", resourceType = "LocalRuntime", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody UpdateLocalRuntimeDTO dto) {
        return ok(localRuntimeService.update(id, dto));
    }

    @AuditLog(action = "ROTATE_TOKEN", resourceType = "LocalRuntime", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{id}/token")
    public ResponseEntity<?> rotateToken(@PathVariable Long id) {
        return ok(localRuntimeService.rotateToken(id));
    }

    @AuditLog(action = "DELETE", resourceType = "LocalRuntime", resourceId = "#id")
    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        localRuntimeService.delete(id);
        return ok(null);
    }
}
