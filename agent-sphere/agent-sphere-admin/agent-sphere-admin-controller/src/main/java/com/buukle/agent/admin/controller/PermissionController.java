package com.buukle.agent.admin.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.dtvo.vo.SysPermissionVO;
import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
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

@RestController
@RequestMapping(ApiPrefix.V1 + "/admin/permissions")
@RequiredArgsConstructor
public class PermissionController extends BaseController {

    private final PermissionSpi permissionSpi;

    @RequirePermission(PermissionCodes.ADMIN_PERMISSION_READ)
    @GetMapping
    public ResponseEntity<Page<SysPermissionVO>> listPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(permissionSpi.listPage(page, size));
    }

    /**
     * Role permission assignment needs the permission tree, but tenant
     * administrators deliberately do not have the permission-management menu.
     * Gate these two endpoints by role permissions instead.
     */
    @RequirePermission(
            value = {PermissionCodes.ADMIN_ROLE_READ, PermissionCodes.ADMIN_ROLE_ASSIGN},
            mode = RequirePermission.MatchMode.OR)
    @GetMapping("/tree")
    public ResponseEntity<List<SysPermissionVO>> listTree() {
        return ok(permissionSpi.listAll());
    }

    @RequirePermission(
            value = {PermissionCodes.ADMIN_ROLE_READ, PermissionCodes.ADMIN_ROLE_ASSIGN},
            mode = RequirePermission.MatchMode.OR)
    @GetMapping("/role/{roleId}")
    public ResponseEntity<List<SysPermissionVO>> listByRoleId(@PathVariable Long roleId) {
        return ok(permissionSpi.listByRoleId(roleId));
    }

    @RequirePermission(PermissionCodes.ADMIN_USER_READ)
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SysPermissionVO>> listByUserId(@PathVariable Long userId) {
        return ok(permissionSpi.listByUserId(userId));
    }

    @AuditLog(action = "CREATE", resourceType = "Permission", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.ADMIN_PERMISSION_CREATE)
    @PostMapping
    public ResponseEntity<SysPermissionVO> create(@RequestBody SysPermissionVO vo) {
        return ok(permissionSpi.create(vo));
    }

    @AuditLog(action = "UPDATE", resourceType = "Permission", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_PERMISSION_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<SysPermissionVO> update(@PathVariable Long id, @RequestBody SysPermissionVO vo) {
        return ok(permissionSpi.update(id, vo));
    }

    @AuditLog(action = "DELETE", resourceType = "Permission", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_PERMISSION_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        permissionSpi.delete(id);
        return ok();
    }
}
