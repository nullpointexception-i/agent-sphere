package com.buukle.agent.common.constant;

/**
 * Full API paths used by interceptors, filters and non-controller services.
 *
 * <p>Controllers should compose their own mappings from {@link ApiPrefix}; this class exists
 * for the few cross-cutting components that need the complete path.
 */
public final class ApiPaths {

    public static final String AUTH_LOGIN = ApiPrefix.AUTH + "/login";
    public static final String AUTH_REGISTER = ApiPrefix.AUTH + "/register";
    public static final String AUTH_CHECK_USERNAME = ApiPrefix.AUTH + "/check-username";
    public static final String AUTH_SSO = ApiPrefix.AUTH + "/sso";
    public static final String AUTH_SSO_EXCHANGE = ApiPrefix.AUTH + "/sso/exchange";
    public static final String AUTH_SSO_CALLBACK = ApiPrefix.AUTH + "/sso/callback";

    public static final String CHROME = ApiPrefix.CHROME;
    public static final String DOCUMENTS_SHARED = ApiPrefix.ARTIFACTS + "/documents/shared";
    public static final String PUBLIC_SHARES = ApiPrefix.PUBLIC + "/shares";
    public static final String SYSTEM_CONFIG_PUBLIC = ApiPrefix.SYSTEM + "/config/public";
    public static final String PLUGIN_DOWNLOAD = ApiPrefix.SYSTEM + "/config/plugin/download";
    public static final String IM_CONVERSATIONS = ApiPrefix.IM + "/conversations/";
    /** Device-token authenticated local runtime endpoints. */
    public static final String LOCAL_RUNTIME_DEVICE = ApiPrefix.V1 + "/local-runtimes/me";

    private ApiPaths() {
    }
}
