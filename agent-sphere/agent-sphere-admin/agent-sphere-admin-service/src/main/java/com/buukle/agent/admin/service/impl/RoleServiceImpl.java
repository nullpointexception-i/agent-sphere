package com.buukle.agent.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.domain.SysPermission;
import com.buukle.agent.admin.domain.SysRole;
import com.buukle.agent.admin.domain.SysRolePermission;
import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.domain.SysUserRole;
import com.buukle.agent.admin.domain.SysUserTenant;
import com.buukle.agent.admin.constant.TenantConstants;
import com.buukle.agent.admin.dtvo.vo.SysRoleVO;
import com.buukle.agent.admin.exception.AdminErrorCode;
import com.buukle.agent.admin.repository.SysPermissionMapper;
import com.buukle.agent.admin.repository.SysRoleMapper;
import com.buukle.agent.admin.repository.SysRolePermissionMapper;
import com.buukle.agent.admin.repository.SysTenantMapper;
import com.buukle.agent.admin.repository.SysUserRoleMapper;
import com.buukle.agent.admin.repository.SysUserTenantMapper;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleSpi {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysTenantMapper tenantMapper;
    private final SysUserTenantMapper userTenantMapper;

    @Override
    public List<SysRoleVO> listAll() {
        return roleMapper.selectList(Wrappers.lambdaQuery(SysRole.class).orderByAsc(SysRole::getId))
                .stream().map(this::toVo).toList();
    }

    @Override
    public Page<SysRoleVO> listPage(int page, int size) {
        var query = Wrappers.lambdaQuery(SysRole.class).orderByAsc(SysRole::getId);
        if (!AuthContext.isSuperAdmin()) {
            TenantScope scope = TenantScopeHolder.get();
            Set<Long> visible = scope == null ? Set.of() : scope.visibleTenantIds();
            if (visible.isEmpty()) {
                var empty = new Page<SysRoleVO>(Math.max(1, page), Math.max(1, size), 0);
                empty.setRecords(List.of());
                return empty;
            }
            query.in(SysRole::getTenantId, visible);
            if (scope.selfOnly()) {
                String username = AuthContext.getUsername();
                if (username == null || username.isBlank()) {
                    var empty = new Page<SysRoleVO>(Math.max(1, page), Math.max(1, size), 0);
                    empty.setRecords(List.of());
                    return empty;
                }
                query.eq(SysRole::getCreatedBy, username);
            }
        }
        var mpPage = roleMapper.selectPage(new Page<>(page, size), query);
        var voPage = new Page<SysRoleVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVo).toList());
        return voPage;
    }

    @Override
    public List<SysRoleVO> listByUserId(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds).stream().map(this::toVo).toList();
    }

    @Override
    public List<SysRoleVO> listByUserId(Long userId, Long tenantId) {
        boolean platformTenant = isPlatformTenant(tenantId);
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream()
                .filter(ur -> belongsToTenant(ur.getTenantId(), tenantId, platformTenant))
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) return List.of();
        return roleMapper.selectBatchIds(roleIds).stream()
                .filter(r -> belongsToTenant(r.getTenantId(), tenantId, platformTenant))
                .map(this::toVo)
                .toList();
    }

    private static boolean isPlatformTenant(Long tenantId) {
        return Long.valueOf(TenantConstants.PLATFORM_TENANT_ID).equals(tenantId);
    }

    /** Platform roles are effective only in the platform tenant. */
    private static boolean belongsToTenant(Long rowTenantId, Long currentTenantId, boolean platformTenant) {
        if (currentTenantId == null) {
            return false;
        }
        if (platformTenant) {
            return rowTenantId == null || rowTenantId.equals(currentTenantId);
        }
        return rowTenantId != null && rowTenantId.equals(currentTenantId);
    }

    @Override
    public List<SysRoleVO> listAssignments(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    public List<SysRoleVO> listAll(Long tenantId) {
        List<SysRole> roles = roleMapper.selectList(Wrappers.lambdaQuery(SysRole.class)
                .orderByAsc(SysRole::getId));
        if (AuthContext.isSuperAdmin()) {
            return roles.stream().map(this::toVo).toList();
        }
        TenantScope scope = TenantScopeHolder.get();
        Set<Long> visible = scope == null ? Set.of() : scope.visibleTenantIds();
        if (visible.isEmpty()) {
            return List.of();
        }
        String username = AuthContext.getUsername();
        return roles.stream()
                .filter(r -> r.getTenantId() != null && visible.contains(r.getTenantId()))
                .filter(r -> !scope.selfOnly()
                        || (username != null && Objects.equals(r.getCreatedBy(), username)))
                .map(this::toVo)
                .toList();
    }

    @Override
    public SysRoleVO create(SysRoleVO vo) {
        Long tenantId = resolveRoleTenantId(vo.getTenantId());
        var query = Wrappers.lambdaQuery(SysRole.class).eq(SysRole::getCode, vo.getCode());
        if (tenantId == null) {
            query.isNull(SysRole::getTenantId);
        } else {
            query.eq(SysRole::getTenantId, tenantId);
        }
        long count = roleMapper.selectCount(query);
        if (count > 0) throw new BizException(AdminErrorCode.ROLE_CODE_EXISTS);
        SysRole entity = toEntity(vo);
        entity.setTenantId(tenantId);
        roleMapper.insert(entity);
        vo.setId(entity.getId());
        vo.setTenantId(tenantId);
        vo.setPlatformRole(tenantId == null);
        return vo;
    }

    @Override
    public SysRoleVO update(Long id, SysRoleVO vo) {
        SysRole entity = roleMapper.selectById(id);
        if (entity == null) throw new BizException(AdminErrorCode.ROLE_NOT_FOUND);
        assertCanManageRole(entity);
        if (vo.getName() != null) entity.setName(vo.getName());
        if (vo.getDescription() != null) entity.setDescription(vo.getDescription());
        roleMapper.updateById(entity);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SysRole entity = roleMapper.selectById(id);
        if (entity != null) {
            assertCanManageRole(entity);
        }
        rolePermissionMapper.delete(Wrappers.lambdaQuery(SysRolePermission.class)
                .eq(SysRolePermission::getRoleId, id));
        userRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class)
                .eq(SysUserRole::getRoleId, id));
        roleMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role != null) {
            assertCanManageRole(role);
        }
        if (!AuthContext.isSuperAdmin()) {
            Set<Long> ownedIds = new HashSet<>();
            Set<String> myPerms = AuthContext.getPermissions();
            if (myPerms != null && !myPerms.isEmpty() && permissionIds != null && !permissionIds.isEmpty()) {
                List<SysPermission> allPerms = permissionMapper.selectBatchIds(permissionIds);
                for (SysPermission p : allPerms) {
                    if (myPerms.contains(p.getCode())) ownedIds.add(p.getId());
                }
            }
            permissionIds = ownedIds.stream().toList();
        }
        rolePermissionMapper.delete(Wrappers.lambdaQuery(SysRolePermission.class)
                .eq(SysRolePermission::getRoleId, roleId));
        if (permissionIds != null && !permissionIds.isEmpty()) {
            List<SysRolePermission> list = permissionIds.stream().map(pid -> {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(pid);
                return rp;
            }).toList();
            list.forEach(rolePermissionMapper::insert);
        }
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        TenantScope scope = TenantScopeHolder.get();
        List<SysRole> roles = roleIds == null || roleIds.isEmpty()
                ? List.of()
                : roleMapper.selectBatchIds(roleIds);
        boolean systemCall = AuthContext.getUserId() == null;
        if (!systemCall && !AuthContext.isSuperAdmin()) {
            if (scope == null || scope.currentTenantId() == null) {
                throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED);
            }
            Set<Long> assignedTenantIds = assignedTenantIds(userId);
            for (SysRole role : roles) {
                if (role.getTenantId() == null) {
                    throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED,
                            "Tenant administrators cannot assign platform roles");
                }
                if (!scope.canManage(role.getTenantId())) {
                    throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED);
                }
                if (!assignedTenantIds.contains(role.getTenantId())) {
                    throw new BizException(AdminErrorCode.ROLE_TENANT_NOT_ASSIGNED);
                }
            }
        }

        // Replace grants inside the operator's management scope. Platform grants
        // and grants in tenants outside that scope are preserved.
        if (systemCall || AuthContext.isSuperAdmin()) {
            userRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class)
                    .eq(SysUserRole::getUserId, userId));
        } else if (scope != null && !scope.manageableTenantIds().isEmpty()) {
            userRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class)
                    .eq(SysUserRole::getUserId, userId)
                    .in(SysUserRole::getTenantId, scope.manageableTenantIds()));
        }

        for (SysRole role : roles) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(role.getId());
            ur.setTenantId(role.getTenantId());
            userRoleMapper.insert(ur);
        }
    }

    private Set<Long> assignedTenantIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return userTenantMapper.selectList(Wrappers.lambdaQuery(SysUserTenant.class)
                        .eq(SysUserTenant::getUserId, userId)
                        .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE))
                .stream()
                .map(SysUserTenant::getTenantId)
                .collect(Collectors.toSet());
    }

    private Long resolveRoleTenantId(Long requestedTenantId) {
        TenantScope scope = TenantScopeHolder.get();
        if (AuthContext.isSuperAdmin()) {
            if (requestedTenantId == null) {
                return null;
            }
            if (scope != null && !scope.canManage(requestedTenantId)) {
                throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED,
                        "Tenant is outside your management scope");
            }
            return requestedTenantId;
        }
        if (scope == null || scope.currentTenantId() == null) {
            throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED,
                    "No current tenant for role creation");
        }
        return scope.currentTenantId();
    }

    private void assertCanManageRole(SysRole role) {
        if (role.getTenantId() == null) {
            if (!AuthContext.isSuperAdmin()) {
                throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED,
                        "Platform roles can only be changed by platform administrators");
            }
            return;
        }
        TenantScope scope = TenantScopeHolder.get();
        if (scope == null || !scope.canManage(role.getTenantId())) {
            throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED,
                    "Role is outside your management scope");
        }
        if (scope.selfOnly() && !Objects.equals(role.getCreatedBy(), AuthContext.getUsername())) {
            throw new BizException(AdminErrorCode.ROLE_SCOPE_DENIED,
                    "SELF_ONLY users can only manage roles they created");
        }
    }

    private SysRoleVO toVo(SysRole role) {
        SysRoleVO vo = new SysRoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setCode(role.getCode());
        vo.setTenantId(role.getTenantId());
        vo.setPlatformRole(role.getTenantId() == null);
        if (role.getTenantId() != null) {
            SysTenant tenant = tenantMapper.selectById(role.getTenantId());
            vo.setTenantName(tenant == null ? null : tenant.getName());
        }
        vo.setDescription(role.getDescription());
        vo.setCreatedAt(role.getCreatedAt());
        return vo;
    }

    private SysRole toEntity(SysRoleVO vo) {
        SysRole entity = new SysRole();
        entity.setName(vo.getName());
        entity.setCode(vo.getCode());
        entity.setTenantId(vo.getTenantId());
        entity.setDescription(vo.getDescription());
        return entity;
    }
}
