package com.buukle.agent.common.context;

import java.util.function.Supplier;

/**
 * Request/task scoped holder for {@link TenantScope}.
 *
 * <p>Kept separate from the legacy {@link TenantUtil} (which stores the
 * {@code created_by} username) so existing audit code can keep working while
 * tenant isolation is introduced incrementally.
 */
public final class TenantScopeHolder {

    private static final InheritableThreadLocal<TenantScope> HOLDER = new InheritableThreadLocal<>();

    private TenantScopeHolder() {
    }

    public static void start(TenantScope scope) {
        if (scope == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(scope);
        }
    }

    public static TenantScope get() {
        return HOLDER.get();
    }

    public static Long currentTenantId() {
        TenantScope scope = HOLDER.get();
        if (scope != null) {
            return scope.currentTenantId();
        }
        return TenantContext.get();
    }

    public static String currentUsername() {
        TenantScope scope = HOLDER.get();
        if (scope != null && scope.username() != null && !scope.username().isBlank()) {
            return scope.username();
        }
        String username = TenantUtil.get();
        if (username != null && !username.isBlank()) {
            return username;
        }
        return AuthContext.getUsername();
    }

    /**
     * Scope used by the SQL interceptor. When a background task only set the
     * legacy numeric tenant context, derive a tenant-only scope so it still
     * filters out other tenants instead of silently bypassing isolation.
     */
    public static TenantScope currentOrFallback() {
        TenantScope scope = HOLDER.get();
        if (scope != null) {
            return scope;
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return null;
        }
        return TenantScope.tenantOnly(tenantId, currentUsername());
    }

    public static <T> T withoutTenantScope(Supplier<T> supplier) {
        TenantScope saved = HOLDER.get();
        HOLDER.remove();
        try {
            return supplier.get();
        } finally {
            if (saved != null) {
                HOLDER.set(saved);
            }
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
