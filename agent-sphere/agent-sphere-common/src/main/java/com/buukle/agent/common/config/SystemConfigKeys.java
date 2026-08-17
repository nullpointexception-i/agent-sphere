package com.buukle.agent.common.config;

public final class SystemConfigKeys {

    public static final String AES_KEY = "crypto.aes-key";
    public static final String CHROME_EXTENSION_TOKEN = "chrome.extension-token";
    public static final String JINA_API_KEY = "web-read.jina-api-key";
    public static final String LOGIN_MAX_ATTEMPTS = "rate-limit.login-max-attempts";
    public static final String LOGIN_WINDOW_MINUTES = "rate-limit.login-window-minutes";
    public static final String SSO_BASE_URL = "sso.base-url";
    /** Chrome 插件安装包下载地址（应用内上传托管时写入托管路由，或管理员手工填外链；空则前端不展示入口） */
    public static final String PLUGIN_DOWNLOAD_URL = "plugin.download-url";
    /** 自助注册用户初始化资源模板（JSON，留空回落 ResourceTemplates.DEFAULT） */
    public static final String USER_RESOURCE_TEMPLATE = "user.resource-template";

    /** 允许不经鉴权公开读取的配置键（仅安全/非敏感项） */
    public static final java.util.Set<String> PUBLIC_KEYS = java.util.Set.of(PLUGIN_DOWNLOAD_URL);

    private SystemConfigKeys() {}
}
