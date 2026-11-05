package com.buukle.agent.common.constant;

/**
 * 文件仓库（agent_file_store）业务桶常量。
 *
 * <p>提取到 common 而非留在各自的 Service 里：历史数据清理任务（SessionCleanupTask，instance 模块）
 * 需要按 biz_key 清理过期截图/附件，但它<b>不能</b>依赖 agent-sphere-infrastructure
 * （infrastructure 已依赖 instance-service，反向依赖会成环）。放 common 才能让两边共用同一份取值。
 */
public final class FileStoreBizKeys {

    /** 聊天附件（AttachmentFileService 使用） */
    public static final String CHAT_ATTACHMENT = "chat-attachment";

    /** 浏览器截图（ScreenshotFileService 使用，插件 CDP 捕获后 base64 上报，单张上限 8MB） */
    public static final String BROWSER_SCREENSHOT = "browser-screenshot";

    /** IM 附件（按会话隔离：bizKey = im-attachment:{conversationId}）。 */
    public static final String IM_ATTACHMENT = "im-attachment";

    private FileStoreBizKeys() {}
}
