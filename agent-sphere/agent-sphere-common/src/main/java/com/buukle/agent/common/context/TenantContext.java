package com.buukle.agent.common.context;

/**
 * Numeric tenant id for new Super-Agent/IM rows.
 *
 * <p>{@link TenantUtil} keeps the username used by the created_by data-permission
 * filter; this context carries the real tenant id so inserts can be isolated.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        if (tenantId == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(tenantId);
        }
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
