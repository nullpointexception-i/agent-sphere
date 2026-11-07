package com.buukle.agent.infrastructure.controller.dtvo;

/** Attachment upload result: the frontend sends the fileKey along with the message, and the runtime reads the file back from it. */
public record AttachmentUploadResult(String fileKey, String contentType, long sizeBytes) {
}
