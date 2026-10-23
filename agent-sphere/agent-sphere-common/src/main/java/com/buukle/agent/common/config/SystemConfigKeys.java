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
    /** Chrome 插件应用市场（Chrome Web Store）下载地址；空则前端不展示应用市场选项 */
    public static final String PLUGIN_STORE_URL = "plugin.store-url";
    /**
     * Chrome 插件最低要求版本（如 {@code 1.0.118}）；空 = 不提示。
     *
     * <p>扩展 popup 用本机 {@code getManifest().version} 与之比较，低于它时展示升级提示。
     * 发布包版本由 {@code deploy.yml} 从 tag 生成（{@code v1.0.NNN-alpha} → {@code 1.0.NNN}），
     * 注意与仓库里 manifest.json 的字面版本无关。
     */
    public static final String PLUGIN_MIN_EXTENSION_VERSION = "plugin.min-extension-version";
    /** 默认资源模板（JSON 数组，新用户初始化时使用；留空则不初始化资源） */
    public static final String USER_RESOURCE_TEMPLATE = "user.resource-template";
    /** 全局 LLM 采样默认配置（JSON，与 instance/completions config 同形状；agent 链路全局兜底） */
    public static final String LLM_DEFAULTS_CONFIG = "llm.defaults-config";
    /** 历史会话清理急停开关（"true"/"false"）：清理是硬删不可逆，出问题先关此项，无需发版 */
    public static final String SESSION_CLEANUP_ENABLED = "session.cleanup-enabled";
    /** 会话数据保留天数：created_at 与 updated_at 均早于该天数的 session 及其关联数据会被清理 */
    public static final String SESSION_CLEANUP_RETENTION_DAYS = "session.cleanup-retention-days";
    /** 截图与聊天附件保留天数（agent_file_store；单张截图上限 8MB，磁盘占用大头，故与文本分开配置） */
    public static final String SESSION_FILE_RETENTION_DAYS = "session.file-retention-days";
    /** 清理任务自身执行记录（agent_session_cleanup_run）的保留天数：记录表本身也要被清理，否则无限增长 */
    public static final String SESSION_CLEANUP_LOG_RETENTION_DAYS = "session.cleanup-log-retention-days";
    /** Skill Hub 自动同步急停开关（"true"/"false"）：关掉后只剩手动触发 */
    public static final String SKILL_AUTO_UPDATE_ENABLED = "skill.auto-update-enabled";
    /** Skill 自动同步执行记录（capability_skill_sync_run）保留天数：每轮扫描一行，不清理会一直涨 */
    public static final String SKILL_SYNC_LOG_RETENTION_DAYS = "skill.sync-log-retention-days";

    /** 允许不经鉴权公开读取的配置键（仅安全/非敏感项） */
    public static final java.util.Set<String> PUBLIC_KEYS =
            java.util.Set.of(PLUGIN_DOWNLOAD_URL, PLUGIN_STORE_URL, PLUGIN_MIN_EXTENSION_VERSION);

    private SystemConfigKeys() {}
}
