package com.buukle.agent.common.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable tenant/data visibility scope for one request or background task.
 *
 * <p>Three concepts are deliberately separate:
 * <ul>
 *   <li>{@link #currentTenantId()} — the tenant new rows belong to and the UI shows;</li>
 *   <li>{@link #visibleTenantIds()} — tenants whose rows may be read (may include descendants);</li>
 *   <li>{@link #selfOnly()} — whether {@code created_by = current user} is also required.</li>
 * </ul>
 *
 * <p>A {@code null} scope means "system task / no tenant filtering". An empty
 * visible set means "the user has no tenant assignment" and therefore must see
 * no tenant-owned rows at all; the interceptor turns it into {@code 1 = 0}.
 */
public final class TenantScope {

    private final Long userId;
    private final String username;
    private final Long currentTenantId;
    private final Set<Long> visibleTenantIds;
    private final boolean selfOnly;
    private final Set<Long> manageableTenantIds;
    /** True when visibility is TENANT_SUBTREE: descendants are in scope. */
    private final boolean subtreeVisible;

    public TenantScope(Long userId, String username, Long currentTenantId,
                       Set<Long> visibleTenantIds, boolean selfOnly,
                       Set<Long> manageableTenantIds) {
        this(userId, username, currentTenantId, visibleTenantIds, selfOnly,
                manageableTenantIds, false);
    }

    public TenantScope(Long userId, String username, Long currentTenantId,
                       Set<Long> visibleTenantIds, boolean selfOnly,
                       Set<Long> manageableTenantIds, boolean subtreeVisible) {
        this.userId = userId;
        this.username = username;
        this.currentTenantId = currentTenantId;
        this.visibleTenantIds = immutableCopy(visibleTenantIds);
        this.selfOnly = selfOnly;
        this.manageableTenantIds = immutableCopy(
                manageableTenantIds == null ? visibleTenantIds : manageableTenantIds);
        this.subtreeVisible = subtreeVisible;
    }

    public static TenantScope empty(Long userId, String username) {
        return new TenantScope(userId, username, null, Set.of(), true, Set.of());
    }

    /**
     * A scope for a background task that only needs to operate inside one tenant.
     * It does not grant visibility into other tenants and never adds self-only.
     */
    public static TenantScope tenantOnly(Long tenantId, String username) {
        if (tenantId == null) {
            return empty(null, username);
        }
        return new TenantScope(null, username, tenantId, Set.of(tenantId), false, Set.of(tenantId));
    }

    private static Set<Long> immutableCopy(Set<Long> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    public Long userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public Long currentTenantId() {
        return currentTenantId;
    }

    public Set<Long> visibleTenantIds() {
        return visibleTenantIds;
    }

    public boolean selfOnly() {
        return selfOnly;
    }

    public boolean subtreeVisible() {
        return subtreeVisible;
    }

    public Set<Long> manageableTenantIds() {
        return manageableTenantIds;
    }

    public boolean isEmptyScope() {
        return visibleTenantIds.isEmpty();
    }

    public boolean visibleTo(Long tenantId) {
        return tenantId != null && visibleTenantIds.contains(tenantId);
    }

    public boolean canManage(Long tenantId) {
        return tenantId != null && manageableTenantIds.contains(tenantId);
    }

    @Override
    public String toString() {
        return "TenantScope{user=" + userId
                + ", current=" + currentTenantId
                + ", visible=" + visibleTenantIds
                + ", selfOnly=" + selfOnly
                + ", subtreeVisible=" + subtreeVisible
                + ", manageable=" + manageableTenantIds + '}';
    }
}
