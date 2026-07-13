package com.buukle.agent.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.dtvo.vo.SysPermissionVO;
import com.buukle.agent.admin.spi.PermissionSpi;
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
@RequestMapping("/api/v1/admin/permissions")
@RequiredArgsConstructor
public class PermissionController extends BaseController {

    private final PermissionSpi permissionSpi;

    @GetMapping
    public ResponseEntity<Page<SysPermissionVO>> listPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(permissionSpi.listPage(page, size));
    }

    @GetMapping("/tree")
    public ResponseEntity<List<SysPermissionVO>> listTree() {
        return ok(permissionSpi.listAll());
    }

    @GetMapping("/role/{roleId}")
    public ResponseEntity<List<SysPermissionVO>> listByRoleId(@PathVariable Long roleId) {
        return ok(permissionSpi.listByRoleId(roleId));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SysPermissionVO>> listByUserId(@PathVariable Long userId) {
        return ok(permissionSpi.listByUserId(userId));
    }

    @RequirePermission("admin:permission:create")
    @PostMapping
    public ResponseEntity<SysPermissionVO> create(@RequestBody SysPermissionVO vo) {
        return ok(permissionSpi.create(vo));
    }

    @RequirePermission("admin:permission:update")
    @PutMapping("/{id}")
    public ResponseEntity<SysPermissionVO> update(@PathVariable Long id, @RequestBody SysPermissionVO vo) {
        return ok(permissionSpi.update(id, vo));
    }

    @RequirePermission("admin:permission:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        permissionSpi.delete(id);
        return ok();
    }
}
