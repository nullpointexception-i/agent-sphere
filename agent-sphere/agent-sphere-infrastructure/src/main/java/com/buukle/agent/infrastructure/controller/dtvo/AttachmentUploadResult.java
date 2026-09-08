package com.buukle.agent.infrastructure.controller.dtvo;

/** 附件上传结果：前端持 fileKey 随消息发送，运行时据此回读文件。 */
public record AttachmentUploadResult(String fileKey, String contentType, long sizeBytes) {
}
