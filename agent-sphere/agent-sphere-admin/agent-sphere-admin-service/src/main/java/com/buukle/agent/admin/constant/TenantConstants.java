package com.buukle.agent.admin.constant;

/** Tenant constants shared by tenant services and tests. */
public final class TenantConstants {

    /** The only root tenant; platform administrators and platform system objects live here. */
    public static final long PLATFORM_TENANT_ID = 1L;

    /** Owner of all legacy users and business data; mounted under the platform tenant. */
    public static final long DEFAULT_TENANT_ID = 2L;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String VISIBILITY_SELF_ONLY = "SELF_ONLY";
    public static final String VISIBILITY_TENANT = "TENANT";
    public static final String VISIBILITY_TENANT_SUBTREE = "TENANT_SUBTREE";

    public static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";
    public static final String ROLE_USER = "USER";

    private TenantConstants() {
    }
}
