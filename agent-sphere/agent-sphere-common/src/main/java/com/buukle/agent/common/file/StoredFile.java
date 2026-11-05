package com.buukle.agent.common.file;

/** Binary file returned by {@link FileStorePort}. */
public record StoredFile(
        String bizKey,
        String fileKey,
        String fileName,
        String contentType,
        long sizeBytes,
        byte[] content) {
}
