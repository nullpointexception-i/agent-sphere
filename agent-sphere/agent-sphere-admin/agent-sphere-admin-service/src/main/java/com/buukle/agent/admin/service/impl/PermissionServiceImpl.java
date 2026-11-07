package com.buukle.agent.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.admin.domain.SysPermission;
import com.buukle.agent.admin.domain.SysRole;
import com.buukle.agent.admin.domain.SysRolePermission;
import com.buukle.agent.admin.domain.SysUserRole;
import com.buukle.agent.admin.constant.TenantConstants;
import com.buukle.agent.admin.dtvo.vo.SysPermissionVO;
import com.buukle.agent.admin.exception.AdminErrorCode;
import com.buukle.agent.admin.repository.SysPermissionMapper;
import com.buukle.agent.admin.repository.SysRoleMapper;
import com.buukle.agent.admin.repository.SysRolePermissionMapper;
import com.buukle.agent.admin.repository.SysUserRoleMapper;
import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionSpi {

    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public List<SysPermissionVO> listAll() {
        List<SysPermission> all = permissionMapper.selectList(
                Wrappers.lambdaQuery(SysPermission.class).orderByAsc(SysPermission::getSort));
        // Role permission assignment must not depend on the tenant visibility
        // scope (SELF_ONLY included): operators assign from their own effective
        // permission set, which matches the no-escalation check in assignPermissions.
        return buildTree(filterByMyTenantPermissions(all), null);
    }

    @Override
    public Page<SysPermissionVO> listPage(int page, int size) {
        String username = AuthContext.getUsername();
        var query = Wrappers.lambdaQuery(SysPermission.class).orderByAsc(SysPermission::getSort);
        if (isSelfOnly()) {
            if (username == null || username.isBlank()) {
                var empty = new Page<SysPermissionVO>(Math.max(1, page), Math.max(1, size), 0);
                empty.setRecords(List.of());
                return empty;
            }
            query.eq(SysPermission::getCreatedBy, username);
        } else {
            List<String> codes = visiblePermissionCodes();
            if (codes != null && codes.isEmpty()) {
                var empty = new Page<SysPermissionVO>(Math.max(1, page), Math.max(1, size), 0);
                empty.setRecords(List.of());
                return empty;
            }
            if (codes != null) {
                if (username == null || username.isBlank()) {
                    query.in(SysPermission::getCode, codes);
                } else {
                    // A newly created permission is not assigned to a role yet;
                    // the creator must still be able to see and assign it.
                    query.and(w -> w.in(SysPermission::getCode, codes)
                            .or().eq(SysPermission::getCreatedBy, username));
                }
            }
        }
        var mpPage = permissionMapper.selectPage(new Page<>(page, size), query);
        var voPage = new Page<SysPermissionVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVo).toList());
        return voPage;
    }

    /** null means platform administrator (no filter); empty means nothing visible. */
    private List<String> visiblePermissionCodes() {
        if (AuthContext.isSuperAdmin()) {
            return null;
        }
        Set<String> codes = AuthContext.getPermissions();
        return codes == null ? List.of() : List.copyOf(codes);
    }

    private static boolean isPlatformTenant(Long tenantId) {
        return Long.valueOf(TenantConstants.PLATFORM_TENANT_ID).equals(tenantId);
    }

    /**
     * Platform roles/grants (tenant_id IS NULL) are only effective inside the
     * platform tenant. A normal tenant only accepts roles/grants whose tenant_id
     * equals that tenant.
     */
    private static boolean belongsToTenant(Long rowTenantId, Long currentTenantId, boolean platformTenant) {
        if (currentTenantId == null) {
            return false;
        }
        if (platformTenant) {
            return rowTenantId == null || rowTenantId.equals(currentTenantId);
        }
        return rowTenantId != null && rowTenantId.equals(currentTenantId);
    }

    private boolean isSelfOnly() {
        return !AuthContext.isSuperAdmin()
                && TenantScopeHolder.get() != null
                && TenantScopeHolder.get().selfOnly();
    }

    /**
     * Permission page visibility: tenant-scoped permission set plus, for
     * SELF_ONLY, only rows created by the current user. The role permission
     * tree deliberately uses {@link #filterByMyTenantPermissions(List)} so an
     * operator can still assign the tenant's permission catalogue.
     */
    private List<SysPermission> filterForPermissionPage(List<SysPermission> all) {
        if (!isSelfOnly()) {
            return filterByMyTenantPermissions(all);
        }
        String username = AuthContext.getUsername();
        if (username == null || username.isBlank()) {
            return List.of();
        }
        Set<Long> keep = new LinkedHashSet<>();
        Map<Long, SysPermission> byId = byId(all);
        for (SysPermission permission : all) {
            if (Objects.equals(permission.getCreatedBy(), username)) {
                addWithAncestors(permission, byId, keep);
            }
        }
        return all.stream().filter(p -> keep.contains(p.getId())).toList();
    }

    private List<SysPermission> filterByMyTenantPermissions(List<SysPermission> all) {
        List<String> codes = visiblePermissionCodes();
        if (codes == null) {
            return all;
        }
        if (codes.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = Set.copyOf(codes);
        String username = AuthContext.getUsername();
        Map<Long, SysPermission> byId = byId(all);
        Set<Long> keep = new LinkedHashSet<>();
        for (SysPermission permission : all) {
            if (!allowed.contains(permission.getCode())
                    && (username == null || !Objects.equals(permission.getCreatedBy(), username))) {
                continue;
            }
            addWithAncestors(permission, byId, keep);
        }
        return all.stream().filter(p -> keep.contains(p.getId())).toList();
    }

    private Map<Long, SysPermission> byId(List<SysPermission> all) {
        return all.stream().collect(Collectors.toMap(SysPermission::getId, p -> p, (a, b) -> a));
    }

    private void addWithAncestors(SysPermission permission, Map<Long, SysPermission> byId, Set<Long> keep) {
        SysPermission current = permission;
        while (current != null && keep.add(current.getId())) {
            Long parentId = current.getParentId();
            current = parentId == null ? null : byId.get(parentId);
        }
    }

    @Override
    public List<SysPermissionVO> listByRoleId(Long roleId) {
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class).eq(SysRolePermission::getRoleId, roleId));
        Set<Long> assignedIds = rps.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toSet());
        List<SysPermission> all = permissionMapper.selectList(
                Wrappers.lambdaQuery(SysPermission.class).orderByAsc(SysPermission::getSort));
        return buildTree(filterByMyTenantPermissions(all), assignedIds);
    }

    @Override
    public List<SysPermissionVO> listByUserId(Long userId) {
        Long tenantId = TenantScopeHolder.currentTenantId();
        boolean platformTenant = isPlatformTenant(tenantId);
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                        Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId))
                .stream()
                .filter(ur -> belongsToTenant(ur.getTenantId(), tenantId, platformTenant))
                .toList();
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        Set<Long> effectiveRoleIds = roleMapper.selectBatchIds(roleIds).stream()
                .filter(r -> belongsToTenant(r.getTenantId(), tenantId, platformTenant))
                .map(SysRole::getId)
                .collect(Collectors.toSet());
        if (effectiveRoleIds.isEmpty()) return List.of();
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class).in(SysRolePermission::getRoleId, effectiveRoleIds));
        Set<Long> permissionIds = rps.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toSet());
        if (permissionIds.isEmpty()) return List.of();
        List<SysPermission> permissions = permissionMapper.selectBatchIds(permissionIds);
        List<String> codes = visiblePermissionCodes();
        return permissions.stream()
                .filter(p -> codes == null || codes.contains(p.getCode()))
                .map(this::toVo)
                .toList();
    }

    @Override
    public List<String> listCodesByUserId(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class).in(SysRolePermission::getRoleId, roleIds));
        if (rps.isEmpty()) return List.of();
        List<Long> permissionIds = rps.stream().map(SysRolePermission::getPermissionId).toList();
        List<SysPermission> permissions = permissionMapper.selectBatchIds(permissionIds);
        return permissions.stream().map(SysPermission::getCode).toList();
    }

    @Override
    public List<String> listCodesByUserId(Long userId, Long tenantId) {
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
        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        Set<Long> effectiveRoleIds = roles.stream()
                .filter(r -> belongsToTenant(r.getTenantId(), tenantId, platformTenant))
                .map(SysRole::getId)
                .collect(Collectors.toSet());
        if (effectiveRoleIds.isEmpty()) return List.of();
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class)
                        .in(SysRolePermission::getRoleId, effectiveRoleIds));
        if (rps.isEmpty()) return List.of();
        Set<Long> permissionIds = rps.stream()
                .map(SysRolePermission::getPermissionId)
                .collect(Collectors.toSet());
        return permissionMapper.selectBatchIds(permissionIds).stream()
                .map(SysPermission::getCode)
                .distinct()
                .toList();
    }

    @Override
    public List<String> listAllCodes() {
        return permissionMapper.selectList(null).stream()
                .map(SysPermission::getCode).toList();
    }

    @Override
    public SysPermissionVO create(SysPermissionVO vo) {
        long count = permissionMapper.selectCount(
                Wrappers.lambdaQuery(SysPermission.class).eq(SysPermission::getCode, vo.getCode()));
        if (count > 0) throw new BizException(AdminErrorCode.PERMISSION_CODE_EXISTS);
        SysPermission entity = toEntity(vo);
        permissionMapper.insert(entity);
        vo.setId(entity.getId());
        return vo;
    }

    @Override
    public SysPermissionVO update(Long id, SysPermissionVO vo) {
        SysPermission entity = permissionMapper.selectById(id);
        if (entity == null) throw new BizException(AdminErrorCode.PERMISSION_CODE_EXISTS);
        if (vo.getName() != null) entity.setName(vo.getName());
        if (vo.getDescription() != null) entity.setDescription(vo.getDescription());
        if (vo.getSort() != null) entity.setSort(vo.getSort());
        permissionMapper.updateById(entity);
        return toVo(entity);
    }

    @Override
    public void delete(Long id) {
        rolePermissionMapper.delete(Wrappers.lambdaQuery(SysRolePermission.class)
                .eq(SysRolePermission::getPermissionId, id));
        permissionMapper.deleteById(id);
    }

    private List<SysPermissionVO> buildTree(List<SysPermission> all, Set<Long> assignedIds) {
        Map<Long, List<SysPermission>> grouped = all.stream()
                .collect(Collectors.groupingBy(p -> p.getParentId() != null ? p.getParentId() : 0L));
        List<SysPermissionVO> tree = new ArrayList<>();
        for (SysPermission p : grouped.getOrDefault(0L, List.of())) {
            SysPermissionVO vo = toVo(p);
            vo.setChildren(buildChildren(p.getId(), grouped, assignedIds));
            tree.add(vo);
        }
        tree.sort(Comparator.comparingInt(SysPermissionVO::getSort));
        return tree;
    }

    private List<SysPermissionVO> buildChildren(Long parentId, Map<Long, List<SysPermission>> grouped,
                                                Set<Long> assignedIds) {
        List<SysPermission> children = grouped.getOrDefault(parentId, List.of());
        return children.stream().map(p -> {
            SysPermissionVO vo = toVo(p);
            vo.setChildren(buildChildren(p.getId(), grouped, assignedIds));
            vo.setAssigned(assignedIds != null && assignedIds.contains(p.getId()));
            return vo;
        }).toList();
    }

    private SysPermissionVO toVo(SysPermission p) {
        SysPermissionVO vo = new SysPermissionVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setCode(p.getCode());
        vo.setType(p.getType());
        vo.setParentId(p.getParentId());
        vo.setSort(p.getSort());
        vo.setDescription(p.getDescription());
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    private SysPermission toEntity(SysPermissionVO vo) {
        SysPermission entity = new SysPermission();
        entity.setName(vo.getName());
        entity.setCode(vo.getCode());
        entity.setType(vo.getType());
        entity.setParentId(vo.getParentId());
        entity.setSort(vo.getSort());
        entity.setDescription(vo.getDescription());
        return entity;
    }
}
