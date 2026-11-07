package com.buukle.agent.runtime.kernel.port.vo;

import java.io.Serializable;

/**
 * A confirmed usable attachment (image). Carries only fileKey (plus content-type);
 * base64 does not travel through Redis queue messages; the kernel reads the bytes back
 * through {@link com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver}
 * only when assembling model messages, and builds the data URL there to avoid bloating the queue.
 */
public record PreparedAttachment(String fileKey, String contentType) implements Serializable {
}