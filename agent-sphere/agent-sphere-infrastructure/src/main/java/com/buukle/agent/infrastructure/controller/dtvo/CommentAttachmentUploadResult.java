package com.buukle.agent.infrastructure.controller.dtvo;

/** Upload result for a requirement-comment attachment. */
public record CommentAttachmentUploadResult(
        String fileKey,
        String fileName,
        String contentType,
        long sizeBytes) {
}
