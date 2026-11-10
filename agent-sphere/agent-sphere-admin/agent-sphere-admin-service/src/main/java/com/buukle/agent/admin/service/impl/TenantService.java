package com.buukle.agent.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.admin.constant.TenantConstants;
import com.buukle.agent.admin.domain.SysPermission;
import com.buukle.agent.admin.domain.SysRole;
import com.buukle.agent.admin.domain.SysRolePermission;
import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.domain.SysUserRole;
import com.buukle.agent.admin.domain.SysUserTenant;
import com.buukle.agent.admin.dtvo.dto.TenantSaveReq;
import com.buukle.agent.admin.dtvo.dto.UserTenantAssignReq;
import com.buukle.agent.admin.dtvo.vo.TenantSwitchVO;
import com.buukle.agent.admin.dtvo.vo.TenantVO;
import com.buukle.agent.admin.dtvo.vo.UserTenantVO;
import com.buukle.agent.admin.exception.AdminErrorCode;
import com.buukle.agent.admin.spi.TenantProvisioningSpi;
import com.buukle.agent.admin.spi.TenantProvisioningSpi.ProvisionedTenant;
import com.buukle.agent.admin.repository.SysPermissionMapper;
import com.buukle.agent.admin.repository.SysRoleMapper;
import com.buukle.agent.admin.repository.SysRolePermissionMapper;
import com.buukle.agent.admin.repository.SysTenantMapper;
import com.buukle.agent.admin.repository.SysUserRoleMapper;
import com.buukle.agent.admin.repository.SysUserTenantMapper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tenant tree, user assignment and current-tenant switching.
 *
 * <p>Writes are always constrained by the caller's manageable tenant set. A
 * platform administrator gets the whole tree through TENANT_SUBTREE; a tenant
 * administrator only sees and manages their own subtree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService implements TenantProvisioningSpi {

    /**
     * Tenant administrators get every permission except the system/security and
     * third-party integration groups. Keep this list in sync with the admin menu
     * groups in the UI.
     */
    private static final List<String> TENANT_ADMIN_EXCLUDED_PERMISSION_PREFIXES = List.of(
            "admin:settings",
            "admin:dictionary",
            "admin:audit-log",
            "admin:identity-provider",
            "admin:permission"
    );

    private final SysTenantMapper tenantMapper;
    private final SysUserTenantMapper userTenantMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final TenantScopeServiceImpl tenantScopeService;
    private final TenantResourceProvisioner tenantResourceProvisioner;

    // ==================== queries ====================

    public List<TenantVO> tree() {
        Set<Long> ids = manageableTenantIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SysTenant> all = tenantMapper.selectList(Wrappers.lambdaQuery(SysTenant.class)
                .in(SysTenant::getId, ids)
                .orderByAsc(SysTenant::getSortOrder)
                .orderByAsc(SysTenant::getId));
        Map<Long, TenantVO> byId = new HashMap<>();
        for (SysTenant t : all) {
            TenantVO vo = toVO(t);
            vo.setManageable(true);
            byId.put(t.getId(), vo);
        }
        List<TenantVO> roots = new ArrayList<>();
        for (SysTenant t : all) {
            TenantVO vo = byId.get(t.getId());
            TenantVO parent = t.getPid() == null ? null : byId.get(t.getPid());
            if (parent == null) {
                roots.add(vo);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    public List<TenantVO> listAll() {
        Set<Long> ids = manageableTenantIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        return tenantMapper.selectList(Wrappers.lambdaQuery(SysTenant.class)
                        .in(SysTenant::getId, ids)
                        .orderByAsc(SysTenant::getSortOrder)
                        .orderByAsc(SysTenant::getId))
                .stream()
                .map(t -> {
                    TenantVO vo = toVO(t);
                    vo.setManageable(true);
                    return vo;
                })
                .toList();
    }

    public List<UserTenantVO> listMyTenants() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return List.of();
        }
        return listUserAssignments(userId);
    }

    public List<UserTenantVO> listUserAssignments(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<SysUserTenant> rows = userTenantMapper.selectList(
                Wrappers.lambdaQuery(SysUserTenant.class)
                        .eq(SysUserTenant::getUserId, userId)
                        .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE)
                        .orderByDesc(SysUserTenant::getIsCurrent)
                        .orderByAsc(SysUserTenant::getTenantId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, SysTenant> tenants = tenantMapper.selectBatchIds(
                        rows.stream().map(SysUserTenant::getTenantId).distinct().toList())
                .stream().collect(Collectors.toMap(SysTenant::getId, t -> t, (a, b) -> a));
        Set<Long> manageable = manageableTenantIds();
        List<UserTenantVO> out = new ArrayList<>(rows.size());
        for (SysUserTenant row : rows) {
            SysTenant tenant = tenants.get(row.getTenantId());
            UserTenantVO vo = new UserTenantVO();
            vo.setTenantId(row.getTenantId());
            vo.setTenantCode(tenant == null ? null : tenant.getCode());
            vo.setTenantName(tenant == null ? "(deleted)" : tenant.getName());
            vo.setTenantStatus(tenant == null ? null : tenant.getStatus());
            vo.setVisibility(row.getVisibility());
            vo.setIsCurrent(row.getIsCurrent());
            vo.setManageable(manageable.contains(row.getTenantId()));
            vo.setCurrentInstanceId(tenant == null ? null : tenant.getCurrentInstanceId());
            out.add(vo);
        }
        return out;
    }

    // ==================== tenant maintenance ====================

    @Transactional
    public SysTenant create(TenantSaveReq req) {
        assertCanCreateChildTenant();
        String code = req.getCode() == null ? "" : req.getCode().trim();
        if (code.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Tenant code is required");
        }
        if (req.getPid() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "A parent tenant is required; only the platform tenant may be root");
        }
        long exists = tenantMapper.selectCount(Wrappers.lambdaQuery(SysTenant.class)
                .eq(SysTenant::getCode, code));
        if (exists > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Tenant code already exists: " + code);
        }
        assertCanManage(req.getPid());
        if (!tenantScopeService.isTenantActive(req.getPid())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Parent tenant does not exist or is disabled");
        }

        SysTenant tenant = new SysTenant();
        tenant.setCode(code);
        tenant.setName(req.getName().trim());
        tenant.setPid(req.getPid());
        tenant.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        tenant.setStatus(TenantConstants.STATUS_ACTIVE);
        tenant.setRemark(req.getRemark());
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);

        createTenantAdminRole(tenant);
        createTenantUserRole(tenant);
        applyTenantResources(tenant);
        return tenant;
    }

    @Override
    @Transactional
    public ProvisionedTenant createUnderPlatform(String code, String name, String remark) {
        String tenantCode = uniqueTenantCode(code);
        String tenantName = name == null || name.isBlank() ? tenantCode : name.trim();
        if (tenantName.length() > 100) {
            tenantName = tenantName.substring(0, 100);
        }
        SysTenant tenant = new SysTenant();
        tenant.setCode(tenantCode);
        tenant.setName(tenantName);
        tenant.setPid(TenantConstants.PLATFORM_TENANT_ID);
        tenant.setSortOrder(0);
        tenant.setStatus(TenantConstants.STATUS_ACTIVE);
        tenant.setRemark(remark);
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);

        createTenantAdminRole(tenant);
        createTenantUserRole(tenant);
        applyTenantResources(tenant);

        SysRole userRole = roleMapper.selectOne(Wrappers.lambdaQuery(SysRole.class)
                .eq(SysRole::getTenantId, tenant.getId())
                .eq(SysRole::getCode, TenantConstants.ROLE_USER)
                .last("LIMIT 1"));
        return new ProvisionedTenant(
                tenant.getId(),
                tenant.getCode(),
                tenant.getName(),
                userRole == null ? null : userRole.getId());
    }

    @Override
    public String findTenantName(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
        return tenant == null ? null : tenant.getName();
    }

    private String uniqueTenantCode(String raw) {
        String base = raw == null ? "SSO" : raw.trim().toUpperCase()
                .replaceAll("[^A-Z0-9_-]", "_");
        if (base.isEmpty()) {
            base = "SSO";
        }
        if (base.length() > 50) {
            base = base.substring(0, 50);
        }
        String candidate = base;
        int suffix = 2;
        while (tenantMapper.selectCount(Wrappers.lambdaQuery(SysTenant.class)
                .eq(SysTenant::getCode, candidate)) > 0) {
            String suffixText = "_" + suffix++;
            int keep = Math.min(base.length(), 50 - suffixText.length());
            candidate = base.substring(0, keep) + suffixText;
        }
        return candidate;
    }

    @Transactional
    public SysTenant update(Long id, TenantSaveReq req) {
        assertCanManage(id);
        if (id.equals(TenantConstants.PLATFORM_TENANT_ID)
                || id.equals(TenantConstants.DEFAULT_TENANT_ID)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "The platform and default tenants are read-only");
        }
        SysTenant existing = tenantMapper.selectById(id);
        if (existing == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "Tenant not found");
        }
        if (req.getCode() != null && !req.getCode().trim().isEmpty()
                && !req.getCode().trim().equals(existing.getCode())) {
            long exists = tenantMapper.selectCount(Wrappers.lambdaQuery(SysTenant.class)
                    .eq(SysTenant::getCode, req.getCode().trim())
                    .ne(SysTenant::getId, id));
            if (exists > 0) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "Tenant code already exists: " + req.getCode());
            }
            existing.setCode(req.getCode().trim());
        }
        if (req.getPid() != null && !req.getPid().equals(existing.getPid())) {
            validateParent(existing.getId(), req.getPid());
            existing.setPid(req.getPid());
        }
        if (req.getName() != null && !req.getName().trim().isEmpty()) {
            existing.setName(req.getName().trim());
        }
        if (req.getSortOrder() != null) {
            existing.setSortOrder(req.getSortOrder());
        }
        if (req.getStatus() != null && !req.getStatus().trim().isEmpty()) {
            String nextStatus = req.getStatus().trim();
            if (TenantConstants.STATUS_DISABLED.equals(nextStatus)
                    && (id.equals(TenantConstants.PLATFORM_TENANT_ID)
                    || id.equals(TenantConstants.DEFAULT_TENANT_ID))) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "The platform and default tenants cannot be disabled");
            }
            if (TenantConstants.STATUS_DISABLED.equals(nextStatus)
                    && id.equals(TenantScopeHolder.currentTenantId())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "You cannot disable your current tenant");
            }
            existing.setStatus(nextStatus);
        }
        existing.setRemark(req.getRemark());
        existing.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(existing);
        return existing;
    }

    /** Runs the tenant resource template and stores the current GROUP_ROUTER instance. */
    private void applyTenantResources(SysTenant tenant) {
        Long currentInstanceId = tenantResourceProvisioner.provision(tenant, operatorName());
        if (currentInstanceId == null) {
            return;
        }
        tenant.setCurrentInstanceId(currentInstanceId);
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
    }

    @Transactional
    public SysTenant setCurrentInstance(Long tenantId, Long instanceId) {
        assertCanManage(tenantId);
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "Tenant not found");
        }
        if (instanceId != null && tenantMapper.countTenantRouterInstance(tenantId, instanceId) == 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "The current instance must be a TENANT GROUP_ROUTER of this tenant");
        }
        tenant.setCurrentInstanceId(instanceId);
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        return tenant;
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        assertCanManage(id);
        if (id.equals(TenantConstants.PLATFORM_TENANT_ID)
                || id.equals(TenantConstants.DEFAULT_TENANT_ID)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "The platform and default tenants are read-only");
        }
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "Tenant not found");
        }
        if (TenantConstants.STATUS_DISABLED.equals(status)
                && id.equals(TenantScopeHolder.currentTenantId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "You cannot disable your current tenant");
        }
        tenant.setStatus(status);
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
    }

    @Transactional
    public void delete(Long id) {
        assertCanManage(id);
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "Tenant not found");
        }
        if (id.equals(TenantConstants.PLATFORM_TENANT_ID)
                || id.equals(TenantConstants.DEFAULT_TENANT_ID)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "The platform and default tenants cannot be deleted");
        }
        long children = tenantMapper.selectCount(Wrappers.lambdaQuery(SysTenant.class)
                .eq(SysTenant::getPid, id));
        if (children > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "Delete or move child tenants first");
        }
        long assignments = userTenantMapper.selectCount(Wrappers.lambdaQuery(SysUserTenant.class)
                .eq(SysUserTenant::getTenantId, id)
                .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE));
        if (assignments > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "Remove all user assignments before deleting the tenant");
        }
        tenantMapper.deleteById(id);
    }

    // ==================== assignments ====================

    @Transactional
    public List<UserTenantVO> assignToUser(UserTenantAssignReq req) {
        Long userId = req.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "userId is required");
        }
        Set<Long> manageable = manageableTenantIds();
        List<UserTenantAssignReq.Assignment> submitted = req.getAssignments() == null
                ? List.of() : req.getAssignments();

        List<SysUserTenant> existing = userTenantMapper.selectList(
                Wrappers.lambdaQuery(SysUserTenant.class)
                        .eq(SysUserTenant::getUserId, userId)
                        .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE));
        List<SysUserTenant> preserved = existing.stream()
                .filter(row -> !manageable.contains(row.getTenantId()))
                .toList();
        if (preserved.stream().anyMatch(row -> Boolean.TRUE.equals(row.getIsCurrent()))) {
            boolean submittedHasCurrent = submitted.stream()
                    .anyMatch(a -> Boolean.TRUE.equals(a.getIsCurrent()));
            if (submittedHasCurrent) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "The user's current tenant is outside your management scope");
            }
        }

        Set<Long> seen = new HashSet<>();
        for (UserTenantAssignReq.Assignment assignment : submitted) {
            if (assignment.getTenantId() == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "tenantId is required");
            }
            if (!manageable.contains(assignment.getTenantId())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "Tenant is outside your management scope: " + assignment.getTenantId());
            }
            if (!seen.add(assignment.getTenantId())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "Duplicate tenant assignment: " + assignment.getTenantId());
            }
            if (!tenantScopeService.isTenantActive(assignment.getTenantId())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "Tenant is disabled or missing: " + assignment.getTenantId());
            }
        }

        long submittedCurrentCount = submitted.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCurrent()))
                .count();
        if (submittedCurrentCount > 1) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "Only one current tenant may be selected");
        }
        boolean hasCurrent = submittedCurrentCount > 0
                || preserved.stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsCurrent()));
        if (!submitted.isEmpty() && !hasCurrent) {
            submitted.get(0).setIsCurrent(true);
            hasCurrent = true;
        }
        if ((!submitted.isEmpty() || !preserved.isEmpty()) && !hasCurrent) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "Exactly one current tenant is required");
        }

        // Replace only the rows inside the operator's management scope.
        if (!manageable.isEmpty()) {
            userTenantMapper.delete(Wrappers.lambdaQuery(SysUserTenant.class)
                    .eq(SysUserTenant::getUserId, userId)
                    .in(SysUserTenant::getTenantId, manageable));
        }
        Set<Long> submittedTenantIds = submitted.stream()
                .map(UserTenantAssignReq.Assignment::getTenantId)
                .collect(Collectors.toSet());
        Set<Long> removedTenantIds = existing.stream()
                .map(SysUserTenant::getTenantId)
                .filter(manageable::contains)
                .filter(tenantId -> !submittedTenantIds.contains(tenantId))
                .collect(Collectors.toSet());
        if (!removedTenantIds.isEmpty()) {
            userRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class)
                    .eq(SysUserRole::getUserId, userId)
                    .in(SysUserRole::getTenantId, removedTenantIds));
        }
        for (UserTenantAssignReq.Assignment assignment : submitted) {
            SysUserTenant row = new SysUserTenant();
            row.setUserId(userId);
            row.setTenantId(assignment.getTenantId());
            row.setVisibility(normalizeVisibility(assignment.getVisibility()));
            row.setIsCurrent(Boolean.TRUE.equals(assignment.getIsCurrent()));
            row.setStatus(TenantConstants.STATUS_ACTIVE);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            userTenantMapper.insert(row);
        }
        return listUserAssignments(userId);
    }

    @Transactional
    public TenantSwitchVO switchCurrentTenant(Long tenantId) {
        Long userId = AuthContext.getUserId();
        if (userId == null || tenantId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Missing user or tenant");
        }
        SysUserTenant target = userTenantMapper.selectOne(
                Wrappers.lambdaQuery(SysUserTenant.class)
                        .eq(SysUserTenant::getUserId, userId)
                        .eq(SysUserTenant::getTenantId, tenantId)
                        .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE)
                        .last("LIMIT 1"));
        if (target == null) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "Tenant is not assigned to you");
        }
        if (!tenantScopeService.isTenantActive(tenantId)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Tenant is disabled");
        }

        SysUserTenant clear = new SysUserTenant();
        clear.setIsCurrent(false);
        clear.setUpdatedAt(LocalDateTime.now());
        userTenantMapper.update(clear, Wrappers.lambdaQuery(SysUserTenant.class)
                .eq(SysUserTenant::getUserId, userId)
                .eq(SysUserTenant::getIsCurrent, true));

        target.setIsCurrent(true);
        target.setUpdatedAt(LocalDateTime.now());
        userTenantMapper.updateById(target);

        SysTenant tenant = tenantMapper.selectById(tenantId);
        TenantSwitchVO vo = new TenantSwitchVO();
        vo.setCurrentTenantId(tenantId);
        vo.setCurrentTenantName(tenant == null ? null : tenant.getName());
        return vo;
    }

    /**
     * A non-platform administrator may create a child tenant only when their
     * visibility includes descendants. Otherwise the new tenant would be
     * immediately invisible to its creator.
     */
    private void assertCanCreateChildTenant() {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        TenantScope scope = TenantScopeHolder.get();
        if (scope == null || !scope.subtreeVisible()) {
            throw new BizException(AdminErrorCode.TENANT_SUBTREE_REQUIRED);
        }
    }

    public void assertCanManage(Long tenantId) {
        TenantScope scope = TenantScopeHolder.get();
        if (scope == null) {
            if (AuthContext.isSuperAdmin()) {
                return;
            }
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        if (!scope.canManage(tenantId)) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "You can only manage tenants inside your own scope");
        }
    }

    // ==================== helpers ====================

    private Set<Long> manageableTenantIds() {
        TenantScope scope = TenantScopeHolder.get();
        if (scope != null) {
            return scope.manageableTenantIds();
        }
        return AuthContext.isSuperAdmin()
                ? tenantScopeService.allActiveTenantIds()
                : Set.of();
    }

    private void validateParent(Long selfId, Long newPid) {
        if (newPid.equals(selfId)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "A tenant cannot be its own parent");
        }
        if (!tenantScopeService.isTenantActive(newPid)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Parent tenant does not exist");
        }
        Set<Long> subtree = tenantScopeService.subtreeIds(selfId);
        if (subtree.contains(newPid)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "A tenant cannot be moved under its own descendant");
        }
    }

    private boolean isTenantAdminPermission(String code) {
        if (code == null) {
            return false;
        }
        for (String prefix : TENANT_ADMIN_EXCLUDED_PERMISSION_PREFIXES) {
            if (code.equals(prefix) || code.startsWith(prefix + ":")) {
                return false;
            }
        }
        return true;
    }

    private void createTenantUserRole(SysTenant tenant) {
        SysRole globalUserRole = roleMapper.selectOne(Wrappers.lambdaQuery(SysRole.class)
                .eq(SysRole::getCode, TenantConstants.ROLE_USER)
                .isNull(SysRole::getTenantId)
                .last("LIMIT 1"));
        if (globalUserRole == null) {
            return;
        }
        SysRole role = new SysRole();
        role.setName("普通用户");
        role.setCode(TenantConstants.ROLE_USER);
        role.setTenantId(tenant.getId());
        role.setDescription("Tenant ordinary user for " + tenant.getName());
        role.setStatus(TenantConstants.STATUS_ACTIVE);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);

        List<SysRolePermission> globalPermissions = rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(SysRolePermission.class)
                        .eq(SysRolePermission::getRoleId, globalUserRole.getId()));
        for (SysRolePermission globalPermission : globalPermissions) {
            SysRolePermission relation = new SysRolePermission();
            relation.setRoleId(role.getId());
            relation.setPermissionId(globalPermission.getPermissionId());
            relation.setStatus(TenantConstants.STATUS_ACTIVE);
            relation.setCreatedAt(LocalDateTime.now());
            relation.setUpdatedAt(LocalDateTime.now());
            rolePermissionMapper.insert(relation);
        }
    }

    private void createTenantAdminRole(SysTenant tenant) {
        SysRole role = new SysRole();
        role.setName("租户管理员");
        role.setCode(TenantConstants.ROLE_TENANT_ADMIN);
        role.setTenantId(tenant.getId());
        role.setDescription("Tenant administrator for " + tenant.getName());
        role.setStatus(TenantConstants.STATUS_ACTIVE);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);

        List<SysPermission> permissions = permissionMapper.selectList(
                        Wrappers.lambdaQuery(SysPermission.class))
                .stream()
                .filter(permission -> isTenantAdminPermission(permission.getCode()))
                .toList();
        for (SysPermission permission : permissions) {
            SysRolePermission relation = new SysRolePermission();
            relation.setRoleId(role.getId());
            relation.setPermissionId(permission.getId());
            relation.setStatus(TenantConstants.STATUS_ACTIVE);
            relation.setCreatedAt(LocalDateTime.now());
            relation.setUpdatedAt(LocalDateTime.now());
            rolePermissionMapper.insert(relation);
        }
    }

    private static String operatorName() {
        String username = AuthContext.getUsername();
        if (username != null && !username.isBlank()) {
            return username;
        }
        String scoped = TenantScopeHolder.currentUsername();
        return scoped == null || scoped.isBlank() ? "system" : scoped;
    }

    private TenantVO toVO(SysTenant tenant) {
        TenantVO vo = new TenantVO();
        vo.setId(tenant.getId());
        vo.setCode(tenant.getCode());
        vo.setName(tenant.getName());
        vo.setPid(tenant.getPid());
        if (tenant.getPid() != null) {
            SysTenant parent = tenantMapper.selectById(tenant.getPid());
            vo.setParentName(parent == null ? null : parent.getName());
        }
        vo.setSortOrder(tenant.getSortOrder());
        vo.setStatus(tenant.getStatus());
        vo.setRemark(tenant.getRemark());
        vo.setCurrentInstanceId(tenant.getCurrentInstanceId());
        vo.setCreatedAt(tenant.getCreatedAt());
        return vo;
    }

    private static String normalizeVisibility(String raw) {
        if (TenantConstants.VISIBILITY_SELF_ONLY.equalsIgnoreCase(raw)) {
            return TenantConstants.VISIBILITY_SELF_ONLY;
        }
        if (TenantConstants.VISIBILITY_TENANT_SUBTREE.equalsIgnoreCase(raw)) {
            return TenantConstants.VISIBILITY_TENANT_SUBTREE;
        }
        return TenantConstants.VISIBILITY_TENANT;
    }
}
