package com.buukle.agent.infrastructure.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件上传（现阶段图片识别）：
 * - POST /upload 登录用户上传图片，返回 {fileKey, contentType, sizeBytes}；
 *   前端随 chat 消息携带 fileKey，运行时回读字节拼 data URL 送模型。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController extends BaseController {

    private final AttachmentFileService attachmentFileService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        return ok(attachmentFileService.upload(file));
    }
}
