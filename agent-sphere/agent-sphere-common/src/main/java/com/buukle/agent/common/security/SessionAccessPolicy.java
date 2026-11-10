package com.buukle.agent.common.security;

import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.constant.SessionTypeCodes;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;

import java.util.Set;

/**
 * Single access rule for sessions.
 *
 * <p>Visibility and operation are deliberately separate:
 * <ul>
 *   <li>{@link #canRead} — tenant administrators may read every session in their visible
 *       tenants; ordinary users only their own, regardless of tenant visibility level.</li>
 *   <li>{@link #canWrite} — only the creator (or a platform super admin) may operate a session;
 *       internal sessions (router / worker / group router) are always read-only.</li>
 * </ul>
 */
public final class SessionAccessPolicy {

    private static final Set<String> INTERNAL_SESSION_TYPES = Set.of(
            SessionTypeCodes.ROUTER,
            SessionTypeCodes.WORKER,
            SessionTypeCodes.GROUP_ROUTER);

    private SessionAccessPolicy() {
    }

    public static boolean canRead(Long tenantId, String createdBy, String sessionType) {
        if (AuthContext.isSuperAdmin()) {
            return true;
        }
        if (!tenantVisible(tenantId)) {
            return false;
        }
        if (isTenantAdmin()) {
            return true;
        }
        return isOwner(createdBy);
    }

    public static boolean canWrite(Long tenantId, String createdBy, String sessionType) {
        if (isInternal(sessionType)) {
            // Internal sessions are read-only from the console; only system/runtime callers may run them.
            return AuthContext.isSuperAdmin() && systemSurface();
        }
        if (AuthContext.isSuperAdmin()) {
            return true;
        }
        // Background/system tasks have no username; they are trusted callers.
        if (AuthContext.getUsername() == null) {
            return true;
        }
        return isOwner(createdBy);
    }

    public static boolean isInternal(String sessionType) {
        return sessionType != null && INTERNAL_SESSION_TYPES.contains(sessionType);
    }

    public static boolean isTenantAdmin() {
        return AuthContext.isSuperAdmin() || AuthContext.hasPermission(PermissionCodes.INSTANCE_TENANT);
    }

    private static boolean tenantVisible(Long tenantId) {
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        return scope == null || tenantId == null || scope.visibleTo(tenantId);
    }

    private static boolean isOwner(String createdBy) {
        String username = AuthContext.getUsername();
        return username != null && username.equals(createdBy);
    }

    private static boolean systemSurface() {
        SessionSurface surface = SessionSurfaceHolder.current();
        return surface == null || surface == SessionSurface.INTERNAL;
    }
}
