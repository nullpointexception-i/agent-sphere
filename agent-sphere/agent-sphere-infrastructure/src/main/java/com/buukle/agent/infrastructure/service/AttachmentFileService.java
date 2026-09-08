package com.buukle.agent.infrastructure.service;

import com.buukle.agent.infrastructure.controller.dtvo.AttachmentUploadResult;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天附件上传：图片经 {@link GenericFileService} 落库（PG bytea），返回 fileKey。
 * 现阶段仅支持图片（MIME 白名单 + 5MB 上限），backend 解析时拼 data URL 送模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentFileService {

    public static final String BIZ_KEY = "chat-attachment";
    private static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final GenericFileService genericFileService;

    public AttachmentUploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("附件不能为空");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (contentType == null) {
            throw new IllegalArgumentException("仅支持图片附件（jpeg/png/webp/gif）");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("附件超过 5MB 上限");
        }
        try {
            String fileKey = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename() == null ? fileKey : file.getOriginalFilename();
            genericFileService.save(BIZ_KEY, fileKey, fileName, contentType, file.getBytes());
            log.info("Attachment stored: fileKey={}, contentType={}, size={}", fileKey, contentType, file.getSize());
            return new AttachmentUploadResult(fileKey, contentType, file.getSize());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("附件保存失败: " + e.getMessage());
        }
    }

    /** 读取附件原始字节并拼接为 OpenAI 兼容 data URL；不存在或非图片返回 null。 */
    public String toDataUrl(String fileKey) {
        StoredFile stored = genericFileService.get(BIZ_KEY, fileKey);
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            return null;
        }
        String contentType = stored.contentType() != null ? stored.contentType() : "application/octet-stream";
        if (!ALLOWED_TYPES.contains(contentType)) {
            return null;
        }
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(stored.content());
    }

    /** 读取附件的 content-type；不存在返回 null。 */
    public String contentTypeOf(String fileKey) {
        StoredFile stored = genericFileService.get(BIZ_KEY, fileKey);
        return stored == null ? null : stored.contentType();
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String normalized = contentType.toLowerCase().split(";")[0].trim();
        return ALLOWED_TYPES.contains(normalized) ? normalized : null;
    }

    /** 附件可用的 content-type；不存在或非图片返回 null（只读元数据，不载字节）。 */
    public String supportedContentTypeOf(String fileKey) {
        String contentType = contentTypeOf(fileKey);
        return contentType != null && ALLOWED_TYPES.contains(contentType) ? contentType : null;
    }

    /** 附件是否为可用的图片（仅看 content-type，不读字节；供发送侧即时校验）。 */
    public boolean isSupportedImage(String fileKey) {
        return supportedContentTypeOf(fileKey) != null;
    }
}
