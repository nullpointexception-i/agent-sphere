package com.buukle.agent.admin.controller;

import com.buukle.agent.admin.dtvo.dto.TenantCurrentInstanceReq;
import com.buukle.agent.admin.dtvo.dto.TenantSaveReq;
import com.buukle.agent.admin.dtvo.dto.UserTenantAssignReq;
import com.buukle.agent.admin.dtvo.vo.TenantSwitchVO;
import com.buukle.agent.admin.dtvo.vo.TenantVO;
import com.buukle.agent.admin.dtvo.vo.UserTenantVO;
import com.buukle.agent.admin.service.impl.TenantService;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.util.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant management and current-tenant switching.
 *
 * <p>Read endpoints are available to every logged-in user because the top bar
 * needs the current user's assignments. Writes require tenant permissions and
 * are additionally constrained by the caller's manageable tenant subtree.
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/admin/tenants")
@RequiredArgsConstructor
public class TenantController extends BaseController {

    private final TenantService tenantService;

    @RequirePermission(PermissionCodes.ADMIN_TENANT_READ)
    @GetMapping("/tree")
    public ResponseEntity<List<TenantVO>> tree() {
        return ok(tenantService.tree());
    }

    @RequirePermission(PermissionCodes.ADMIN_TENANT_READ)
    @GetMapping
    public ResponseEntity<List<TenantVO>> list() {
        return ok(tenantService.listAll());
    }

    @GetMapping("/mine")
    public ResponseEntity<List<UserTenantVO>> mine() {
        return ok(tenantService.listMyTenants());
    }

    @RequirePermission(PermissionCodes.ADMIN_USER_READ)
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<UserTenantVO>> listUserAssignments(@PathVariable Long userId) {
        return ok(tenantService.listUserAssignments(userId));
    }

    @AuditLog(action = "CREATE", resourceType = "Tenant", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.ADMIN_TENANT_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TenantSaveReq req) {
        return ok(tenantService.create(req));
    }

    @AuditLog(action = "UPDATE", resourceType = "Tenant", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_TENANT_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody TenantSaveReq req) {
        return ok(tenantService.update(id, req));
    }

    @AuditLog(action = "CURRENT_INSTANCE", resourceType = "Tenant", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_TENANT_UPDATE)
    @PutMapping("/{id}/current-instance")
    public ResponseEntity<?> setCurrentInstance(@PathVariable Long id,
                                                @Valid @RequestBody TenantCurrentInstanceReq req) {
        return ok(tenantService.setCurrentInstance(id, req.getInstanceId()));
    }

    @AuditLog(action = "STATUS", resourceType = "Tenant", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_TENANT_UPDATE)
    @PutMapping("/{id}/status")
    public ResponseEntity<?> changeStatus(@PathVariable Long id, @RequestParam String status) {
        tenantService.changeStatus(id, status);
        return ok();
    }

    @AuditLog(action = "DELETE", resourceType = "Tenant", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_TENANT_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        tenantService.delete(id);
        return ok();
    }

    @AuditLog(action = "ASSIGN", resourceType = "Tenant", resourceId = "#req.userId")
    @RequirePermission(PermissionCodes.ADMIN_TENANT_ASSIGN)
    @PostMapping("/assign")
    public ResponseEntity<List<UserTenantVO>> assign(@Valid @RequestBody UserTenantAssignReq req) {
        return ok(tenantService.assignToUser(req));
    }

    @AuditLog(action = "SWITCH", resourceType = "Tenant", resourceId = "#tenantId")
    @PostMapping("/switch")
    public ResponseEntity<TenantSwitchVO> switchCurrent(@RequestParam Long tenantId) {
        return ok(tenantService.switchCurrentTenant(tenantId));
    }
}
