package com.buukle.agent.infrastructure.service;

import com.buukle.agent.common.constant.FileStoreBizKeys;
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
 * Chat attachment upload: images are persisted via {@link GenericFileService} (PG bytea) and a fileKey is
 * returned. At this stage only images are supported (MIME whitelist + 5 MB cap); the backend builds a
 * data URL when parsing and sends it to the model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentFileService {

    /** See {@link FileStoreBizKeys#CHAT_ATTACHMENT}; this field is kept so existing call sites are unchanged */
    public static final String BIZ_KEY = FileStoreBizKeys.CHAT_ATTACHMENT;
    private static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final GenericFileService genericFileService;

    public AttachmentUploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("attachment must not be empty");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (contentType == null) {
            throw new IllegalArgumentException("only image attachments are supported (jpeg/png/webp/gif)");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("attachment exceeds the 5 MB limit");
        }
        try {
            String fileKey = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename() == null ? fileKey : file.getOriginalFilename();
            genericFileService.save(BIZ_KEY, fileKey, fileName, contentType, file.getBytes());
            log.info("Attachment stored: fileKey={}, contentType={}, size={}", fileKey, contentType, file.getSize());
            return new AttachmentUploadResult(fileKey, contentType, file.getSize());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to save attachment: " + e.getMessage());
        }
    }

    /** Reads the raw attachment bytes and assembles an OpenAI-compatible data URL; returns null if missing or not an image. */
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

    /** Reads the attachment's content-type; returns null if it does not exist. */
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

    /** Content-type available for the attachment; returns null if missing or not an image (metadata only, bytes not loaded). */
    public String supportedContentTypeOf(String fileKey) {
        String contentType = contentTypeOf(fileKey);
        return contentType != null && ALLOWED_TYPES.contains(contentType) ? contentType : null;
    }

    /** Whether the attachment is a usable image (content-type only, bytes not read; used for immediate validation on the send side). */
    public boolean isSupportedImage(String fileKey) {
        return supportedContentTypeOf(fileKey) != null;
    }

    /** Reads the raw attachment file; returns null if missing (used by the frontend to fetch bytes by fileKey for display). */
    public StoredFile load(String fileKey) {
        return genericFileService.get(BIZ_KEY, fileKey);
    }
}
