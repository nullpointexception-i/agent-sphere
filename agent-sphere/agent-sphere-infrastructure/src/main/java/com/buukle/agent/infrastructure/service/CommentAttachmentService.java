package com.buukle.agent.infrastructure.service;

import com.buukle.agent.common.constant.FileStoreBizKeys;
import com.buukle.agent.infrastructure.controller.dtvo.CommentAttachmentUploadResult;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/** Generic requirement-comment attachments, stored in the shared file store. */
@Service
@RequiredArgsConstructor
public class CommentAttachmentService {

    private static final long MAX_ATTACHMENT_SIZE_BYTES = 20L * 1024 * 1024;

    private final GenericFileService genericFileService;

    public CommentAttachmentUploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("attachment must not be empty");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new IllegalArgumentException("attachment exceeds the 20 MB limit");
        }
        try {
            String fileKey = UUID.randomUUID().toString();
            String fileName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename()
                    : fileKey;
            String contentType = StringUtils.hasText(file.getContentType())
                    ? file.getContentType()
                    : "application/octet-stream";
            genericFileService.save(
                    FileStoreBizKeys.REQUIREMENT_COMMENT_ATTACHMENT,
                    fileKey, fileName, contentType, file.getBytes());
            return new CommentAttachmentUploadResult(
                    fileKey, fileName, contentType, file.getSize());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to save comment attachment: " + e.getMessage());
        }
    }

    public StoredFile load(String fileKey) {
        return genericFileService.get(
                FileStoreBizKeys.REQUIREMENT_COMMENT_ATTACHMENT, fileKey);
    }
}
