package com.buukle.agent.infrastructure.controller.dtvo;

/** 浏览器截图上传结果（仅 fileKey+content-type，前端/回调只带引用，字节留 agent_file_store）。 */
public record ScreenshotUploadResult(String fileKey, String contentType, long sizeBytes) {
}