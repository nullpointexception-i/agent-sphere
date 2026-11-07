package com.buukle.agent.infrastructure.file;

/** Binary file info returned by the generic file store. */
public record StoredFile(
        String bizKey,
        String fileKey,
        String fileName,
        String contentType,
        long sizeBytes,
        byte[] content) {
}