package com.buukle.agent.infrastructure.controller.dtvo;

/** Browser screenshot upload request: the extension uploads base64, and the backend persists it and returns a fileKey. */
public record ScreenshotUploadDTO(String contentType, String base64) {
}