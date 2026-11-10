package com.buukle.agent.common.constant;

/**
 * Resource ownership scope.
 *
 * <p>{@link #USER} resources are private to their creator; {@link #TENANT}
 * resources belong to the tenant and are maintained by tenant administrators.
 * Stored as a varchar column, so these constants are also the database values.
 */
public final class OwnerScope {

    public static final String USER = "USER";
    public static final String TENANT = "TENANT";

    private OwnerScope() {
    }

    public static String normalize(String raw) {
        return TENANT.equalsIgnoreCase(raw) ? TENANT : USER;
    }

    public static boolean isTenant(String raw) {
        return TENANT.equalsIgnoreCase(raw);
    }
}
