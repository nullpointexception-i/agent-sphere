package com.buukle.agent.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.dtvo.dto.AssignPermissionDTO;
import com.buukle.agent.admin.dtvo.dto.AssignRoleDTO;
import com.buukle.agent.admin.dtvo.vo.SysRoleVO;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
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

@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
public class RoleController extends BaseController {

    private final RoleSpi roleSpi;

    @GetMapping
    public ResponseEntity<Page<SysRoleVO>> listPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(roleSpi.listPage(page, size));
    }

    @GetMapping("/all")
    public ResponseEntity<List<SysRoleVO>> listAll() {
        return ok(roleSpi.listAll());
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SysRoleVO>> listByUserId(@PathVariable Long userId) {
        return ok(roleSpi.listByUserId(userId));
    }

    @AuditLog(action = "CREATE", resourceType = "Role", resourceId = "#result?.body?.id")
    @RequirePermission("admin:role:create")
    @PostMapping
    public ResponseEntity<SysRoleVO> create(@RequestBody SysRoleVO vo) {
        return ok(roleSpi.create(vo));
    }

    @AuditLog(action = "UPDATE", resourceType = "Role", resourceId = "#id")
    @RequirePermission("admin:role:update")
    @PutMapping("/{id}")
    public ResponseEntity<SysRoleVO> update(@PathVariable Long id, @RequestBody SysRoleVO vo) {
        return ok(roleSpi.update(id, vo));
    }

    @AuditLog(action = "DELETE", resourceType = "Role", resourceId = "#id")
    @RequirePermission("admin:role:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        roleSpi.delete(id);
        return ok();
    }

    @AuditLog(action = "ASSIGN_PERMISSION", resourceType = "Role", resourceId = "#dto.roleId")
    @RequirePermission("admin:role:assign")
    @PostMapping("/assign-permissions")
    public ResponseEntity<?> assignPermissions(@Valid @RequestBody AssignPermissionDTO dto) {
        roleSpi.assignPermissions(dto.getRoleId(), dto.getPermissionIds());
        return ok();
    }

    @AuditLog(action = "ASSIGN_ROLE", resourceType = "User", resourceId = "#dto.userId")
    @RequirePermission("admin:role:assign")
    @PostMapping("/assign-user")
    public ResponseEntity<?> assignUser(@Valid @RequestBody AssignRoleDTO dto) {
        roleSpi.assignRoles(dto.getUserId(), dto.getRoleIds());
        return ok();
    }
}
