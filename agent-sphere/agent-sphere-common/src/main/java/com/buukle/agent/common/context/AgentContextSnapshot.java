package com.buukle.agent.common.context;

import java.util.Set;

/**
 * Captures the request/task scoped context so internal SPI calls can switch to a system context
 * without leaking the change back to the caller.
 */
public final class AgentContextSnapshot {

    private final Long tenantId;
    private final String tenantUser;
    private final TenantScope tenantScope;
    private final Long userId;
    private final String username;
    private final String displayName;
    private final Boolean superAdmin;
    private final Set<String> permissions;
    private final SessionSurface surface;

    private AgentContextSnapshot() {
        this.tenantId = TenantContext.get();
        this.tenantUser = TenantUtil.get();
        this.tenantScope = TenantScopeHolder.get();
        this.userId = AuthContext.getUserId();
        this.username = AuthContext.getUsername();
        this.displayName = AuthContext.getDisplayName();
        this.superAdmin = AuthContext.isSuperAdmin();
        this.permissions = AuthContext.getPermissions();
        this.surface = SessionSurfaceHolder.get();
    }

    public static AgentContextSnapshot capture() {
        return new AgentContextSnapshot();
    }

    public void restore() {
        restoreTenant();
        restoreAuth();
        if (surface == null) {
            SessionSurfaceHolder.clear();
        } else {
            SessionSurfaceHolder.set(surface);
        }
    }

    private void restoreTenant() {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
        if (tenantUser == null) {
            TenantUtil.stop();
        } else {
            TenantUtil.start(tenantUser);
        }
        if (tenantScope == null) {
            TenantScopeHolder.clear();
        } else {
            TenantScopeHolder.start(tenantScope);
        }
    }

    private void restoreAuth() {
        if (userId == null) {
            AuthContext.setUserId(null);
        } else {
            AuthContext.setUserId(userId);
        }
        AuthContext.setUsername(username);
        AuthContext.setDisplayName(displayName);
        AuthContext.setSuperAdmin(Boolean.TRUE.equals(superAdmin));
        AuthContext.setPermissions(permissions);
    }
}
