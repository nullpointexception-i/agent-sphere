package com.buukle.agent.runtime.kernel.port;

/**
 * Attachment byte read-back port: when the kernel assembles model messages (content parts) it lazily
 * loads the image by fileKey and builds an OpenAI-compatible data URL. The implementation is provided by the orchestration layer
 * (delegating to infrastructure file storage); the kernel does not depend on infrastructure.
 */
public interface ChatAttachmentResolver {

    /** Reads image bytes back by fileKey and builds a data URL; returns null when missing or not an image. */
    String toDataUrl(String fileKey);
}