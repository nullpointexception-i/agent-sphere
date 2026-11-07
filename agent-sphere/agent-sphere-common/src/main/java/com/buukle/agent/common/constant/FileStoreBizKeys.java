package com.buukle.agent.common.constant;

/**
 * Business bucket constants for the file store (agent_file_store).
 *
 * <p>Kept in common instead of each owning Service: the historical data cleanup task
 * (SessionCleanupTask, instance module) needs to purge expired screenshots/attachments by biz_key,
 * but it <b>cannot</b> depend on agent-sphere-infrastructure (infrastructure already depends on
 * instance-service, so the reverse dependency would form a cycle). Only common lets both sides
 * share the same values.
 */
public final class FileStoreBizKeys {

    /** Chat attachment (used by AttachmentFileService) */
    public static final String CHAT_ATTACHMENT = "chat-attachment";

    /** Browser screenshot (used by ScreenshotFileService; captured via the extension's CDP and reported as base64, 8MB cap per image) */
    public static final String BROWSER_SCREENSHOT = "browser-screenshot";

    /** IM attachment (isolated per conversation: bizKey = im-attachment:{conversationId}). */
    public static final String IM_ATTACHMENT = "im-attachment";

    private FileStoreBizKeys() {}
}
