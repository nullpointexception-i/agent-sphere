package com.buukle.agent.infrastructure.controller.dtvo;

/** Browser screenshot upload result (only fileKey + content-type; the frontend/callback carries just the reference, bytes stay in agent_file_store). */
public record ScreenshotUploadResult(String fileKey, String contentType, long sizeBytes) {
}