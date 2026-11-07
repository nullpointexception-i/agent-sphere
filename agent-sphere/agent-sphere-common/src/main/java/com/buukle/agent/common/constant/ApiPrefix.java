package com.buukle.agent.common.constant;

/**
 * Versioned API prefixes shared by every controller.
 *
 * <p>Controllers must compose their mappings from these constants instead of repeating
 * the literal {@code /api/v1} path. The value is part of the public HTTP contract and
 * must not change without a versioned migration.
 */
public final class ApiPrefix {

    public static final String V1 = "/api/v1";
    public static final String API = V1 + "/api";
    public static final String ADMIN = V1 + "/admin";
    public static final String INSTANCE = V1 + "/instance";
    public static final String RUNTIME = V1 + "/runtime";
    public static final String MODEL = V1 + "/model";
    public static final String CAPABILITY = V1 + "/capability";
    public static final String SUPER_AGENT = V1 + "/super-agents";
    public static final String IM = V1 + "/im";
    public static final String AUTH = V1 + "/auth";
    public static final String SSO = V1 + "/sso";
    public static final String FILES = V1 + "/files";
    public static final String ARTIFACTS = V1 + "/artifacts";
    public static final String CHANNELS = V1 + "/channels";
    public static final String SYSTEM = V1 + "/system";
    public static final String PUBLIC = V1 + "/public";
    public static final String WORKSPACE = V1 + "/workspace";
    public static final String CHROME = V1 + "/chrome";
    public static final String BROWSER = V1 + "/browser";

    private ApiPrefix() {
    }
}
