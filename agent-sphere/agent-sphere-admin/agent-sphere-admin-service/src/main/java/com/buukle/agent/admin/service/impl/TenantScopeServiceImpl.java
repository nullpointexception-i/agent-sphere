package com.buukle.agent.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.admin.constant.TenantConstants;
import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.domain.SysUserTenant;
import com.buukle.agent.admin.repository.SysTenantMapper;
import com.buukle.agent.admin.repository.SysUserTenantMapper;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the per-request tenant scope from {@code sys_user_tenant}.
 *
 * <p>No caching by design: assignment and switch changes must take effect on
 * the next request. The tenant tree is small, so two simple queries are cheaper
 * than the invalidation bugs a cache would introduce here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantScopeServiceImpl implements TenantScopeSpi {

    private static final int MAX_TENANT_DEPTH = 32;

    private final SysTenantMapper tenantMapper;
    private final SysUserTenantMapper userTenantMapper;

    @Override
    public TenantScope resolve(Long userId, String username) {
        if (userId == null) {
            return TenantScope.empty(null, username);
        }
        try {
            List<SysUserTenant> assignments = activeAssignments(userId);
            if (assignments.isEmpty()) {
                return TenantScope.empty(userId, username);
            }

            SysUserTenant current = assignments.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIsCurrent()))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                log.warn("[Tenant] user has no current tenant userId={}", userId);
                return TenantScope.empty(userId, username);
            }
            if (!isTenantActive(current.getTenantId())) {
                log.warn("[Tenant] current tenant is disabled userId={} tenantId={}",
                        userId, current.getTenantId());
                return TenantScope.empty(userId, username);
            }

            Set<Long> visible = new LinkedHashSet<>();
            visible.add(current.getTenantId());
            String visibility = normalizeVisibility(current.getVisibility());
            if (TenantConstants.VISIBILITY_TENANT_SUBTREE.equals(visibility)) {
                visible.addAll(descendantIds(current.getTenantId()));
            }

            Set<Long> manageable;
            if (AuthContext.isSuperAdmin()) {
                manageable = allActiveTenantIds();
            } else {
                manageable = new LinkedHashSet<>(visible);
            }

            return new TenantScope(
                    userId,
                    username,
                    current.getTenantId(),
                    visible,
                    TenantConstants.VISIBILITY_SELF_ONLY.equals(visibility),
                    manageable,
                    TenantConstants.VISIBILITY_TENANT_SUBTREE.equals(visibility));
        } catch (Exception e) {
            log.error("[Tenant] failed to resolve scope, fail closed userId={}", userId, e);
            return TenantScope.empty(userId, username);
        }
    }

    @Override
    public void ensureDefaultAssignment(Long userId) {
        if (userId == null) {
            return;
        }
        List<SysUserTenant> assignments = activeAssignments(userId);
        if (assignments.isEmpty()) {
            SysUserTenant row = new SysUserTenant();
            row.setUserId(userId);
            row.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
            row.setVisibility(TenantConstants.VISIBILITY_SELF_ONLY);
            row.setIsCurrent(true);
            row.setStatus(TenantConstants.STATUS_ACTIVE);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            userTenantMapper.insert(row);
            return;
        }
        boolean hasCurrent = assignments.stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.getIsCurrent()));
        if (!hasCurrent) {
            SysUserTenant first = assignments.get(0);
            first.setIsCurrent(true);
            first.setUpdatedAt(LocalDateTime.now());
            userTenantMapper.updateById(first);
        }
    }

    @Override
    public List<Long> listUserIdsInTenants(Set<Long> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        return userTenantMapper.selectList(Wrappers.lambdaQuery(SysUserTenant.class)
                        .in(SysUserTenant::getTenantId, tenantIds)
                        .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE))
                .stream()
                .map(SysUserTenant::getUserId)
                .distinct()
                .toList();
    }

    @Override
    public void ensureAssignment(Long userId, Long tenantId) {
        if (userId == null || tenantId == null) {
            return;
        }
        if (!isTenantActive(tenantId)) {
            throw new com.buukle.agent.common.exception.BizException(
                    com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                    "Tenant is disabled or missing: " + tenantId);
        }
        List<SysUserTenant> assignments = activeAssignments(userId);
        SysUserTenant target = assignments.stream()
                .filter(a -> tenantId.equals(a.getTenantId()))
                .findFirst()
                .orElse(null);

        SysUserTenant clear = new SysUserTenant();
        clear.setIsCurrent(false);
        clear.setUpdatedAt(LocalDateTime.now());
        userTenantMapper.update(clear, Wrappers.lambdaQuery(SysUserTenant.class)
                .eq(SysUserTenant::getUserId, userId)
                .eq(SysUserTenant::getIsCurrent, true));

        if (target == null) {
            SysUserTenant row = new SysUserTenant();
            row.setUserId(userId);
            row.setTenantId(tenantId);
            row.setVisibility(TenantConstants.VISIBILITY_SELF_ONLY);
            row.setIsCurrent(true);
            row.setStatus(TenantConstants.STATUS_ACTIVE);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            userTenantMapper.insert(row);
            return;
        }
        target.setVisibility(TenantConstants.VISIBILITY_SELF_ONLY);
        target.setIsCurrent(true);
        target.setStatus(TenantConstants.STATUS_ACTIVE);
        target.setUpdatedAt(LocalDateTime.now());
        userTenantMapper.updateById(target);
    }

    @Override
    public Long defaultTenantId() {
        return TenantConstants.DEFAULT_TENANT_ID;
    }

    @Override
    public boolean isTenantActive(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        return tenantMapper.selectCount(Wrappers.lambdaQuery(SysTenant.class)
                .eq(SysTenant::getId, tenantId)
                .eq(SysTenant::getStatus, TenantConstants.STATUS_ACTIVE)) > 0;
    }

    public List<SysUserTenant> activeAssignments(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userTenantMapper.selectList(Wrappers.lambdaQuery(SysUserTenant.class)
                .eq(SysUserTenant::getUserId, userId)
                .eq(SysUserTenant::getStatus, TenantConstants.STATUS_ACTIVE)
                .orderByDesc(SysUserTenant::getIsCurrent)
                .orderByAsc(SysUserTenant::getTenantId));
    }

    public Set<Long> allActiveTenantIds() {
        List<SysTenant> tenants = tenantMapper.selectList(Wrappers.lambdaQuery(SysTenant.class)
                .eq(SysTenant::getStatus, TenantConstants.STATUS_ACTIVE)
                .orderByAsc(SysTenant::getSortOrder)
                .orderByAsc(SysTenant::getId));
        Set<Long> ids = new LinkedHashSet<>();
        for (SysTenant tenant : tenants) {
            ids.add(tenant.getId());
        }
        return ids;
    }

    public Set<Long> subtreeIds(Long tenantId) {
        Set<Long> result = new LinkedHashSet<>();
        if (tenantId == null) {
            return result;
        }
        result.add(tenantId);
        result.addAll(descendantIds(tenantId));
        return result;
    }

    @Override
    public List<Long> ancestorTenantIds(Long tenantId) {
        List<Long> result = new ArrayList<>();
        if (tenantId == null) {
            return result;
        }
        Long cursor = tenantId;
        int depth = 0;
        while (cursor != null && depth++ < MAX_TENANT_DEPTH) {
            SysTenant tenant = tenantMapper.selectById(cursor);
            if (tenant == null || !TenantConstants.STATUS_ACTIVE.equals(tenant.getStatus())) {
                break;
            }
            result.add(cursor);
            cursor = tenant.getPid();
        }
        return result;
    }

    private Set<Long> descendantIds(Long tenantId) {
        List<SysTenant> all = tenantMapper.selectList(Wrappers.lambdaQuery(SysTenant.class)
                .eq(SysTenant::getStatus, TenantConstants.STATUS_ACTIVE)
                .orderByAsc(SysTenant::getSortOrder)
                .orderByAsc(SysTenant::getId));
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (SysTenant tenant : all) {
            childrenByParent.computeIfAbsent(tenant.getPid(), k -> new ArrayList<>())
                    .add(tenant.getId());
        }
        Set<Long> collected = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(tenantId);
        while (!queue.isEmpty()) {
            Long pid = queue.poll();
            for (Long child : childrenByParent.getOrDefault(pid, List.of())) {
                if (collected.add(child)) {
                    queue.add(child);
                }
            }
        }
        return collected;
    }

    @Override
    public Long groupRouterModelRouteId(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
        return tenant == null ? null : tenant.getGroupRouterModelRouteId();
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
