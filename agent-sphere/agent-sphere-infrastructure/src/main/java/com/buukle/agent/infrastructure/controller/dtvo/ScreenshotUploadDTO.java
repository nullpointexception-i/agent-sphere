package com.buukle.agent.infrastructure.controller.dtvo;

/** 浏览器截图上报请求：扩展以 base64 上传，后台落库并回传 fileKey。 */
public record ScreenshotUploadDTO(String contentType, String base64) {
}