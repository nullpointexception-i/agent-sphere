package com.buukle.agent.runtime.kernel.port.vo;

import java.io.Serializable;

/**
 * 已确认可用的附件（图片）。仅携带 fileKey（与 content-type），
 * base64 不随 Redis 队列消息流转；kernel 在组模型消息时才
 * 通过 {@link com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver}
 * 回读字节拼 data URL，避免队列体积膨胀。
 */
public record PreparedAttachment(String fileKey, String contentType) implements Serializable {
}