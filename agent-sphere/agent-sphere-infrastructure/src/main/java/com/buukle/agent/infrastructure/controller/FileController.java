package com.buukle.agent.infrastructure.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.infrastructure.file.StoredFile;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件（现阶段图片识别）：
 * - POST /upload 登录用户上传图片，返回 {fileKey, contentType, sizeBytes}；
 *   前端随 chat 消息携带 fileKey，运行时回读字节拼 data URL 送模型。
 * - GET  /{fileKey} 按 fileKey 回读原始字节（登录保护），前端聊天历史回显图片用。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController extends BaseController {

    private final AttachmentFileService attachmentFileService;
    private final ScreenshotFileService screenshotFileService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        return ok(attachmentFileService.upload(file));
    }

    @GetMapping("/{fileKey}")
    public ResponseEntity<?> download(@PathVariable("fileKey") String fileKey) {
        StoredFile stored = attachmentFileService.load(fileKey);
        if (stored == null) {
            // 浏览器截图（browser-screenshot 桶）：聊天附件查不到时兜底
            stored = screenshotFileService.load(fileKey);
        }
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        MediaType mediaType = MediaType.parseMediaType(
                stored.contentType() != null ? stored.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(stored.content().length)
                .body(stored.content());
    }
}