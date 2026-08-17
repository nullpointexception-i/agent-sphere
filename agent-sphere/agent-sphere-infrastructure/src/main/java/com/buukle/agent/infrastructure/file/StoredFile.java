package com.buukle.agent.infrastructure.file;

/** 通用文件存储返回的二进制文件信息。 */
public record StoredFile(
        String bizKey,
        String fileKey,
        String fileName,
        String contentType,
        long sizeBytes,
        byte[] content) {
}