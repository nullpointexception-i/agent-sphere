package com.buukle.agent.infrastructure.service;

import com.buukle.agent.common.constant.FileStoreBizKeys;
import com.buukle.agent.infrastructure.controller.dtvo.ScreenshotUploadResult;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * Browser screenshot persistence: the extension captures a screenshot and uploads it as base64, stored via
 * {@link GenericFileService} under (browser-screenshot, fileKey). Screenshot files and chat attachments
 * coexist in agent_file_store, differing only by bizKey.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenshotFileService {

    /** See {@link FileStoreBizKeys#BROWSER_SCREENSHOT}; this field is kept so existing call sites are unchanged */
    public static final String BIZ_KEY = FileStoreBizKeys.BROWSER_SCREENSHOT;
    private static final long MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final GenericFileService genericFileService;

    /** Persists a base64 screenshot; throws IllegalArgumentException when contentType is outside the whitelist or the size cap is exceeded. */
    public ScreenshotUploadResult store(String base64, String contentType) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("screenshot content must not be empty");
        }
        String normalized = normalizeContentType(contentType);
        if (normalized == null) {
            throw new IllegalArgumentException("only jpeg/png/webp screenshots are supported");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("failed to decode screenshot base64");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("screenshot content must not be empty");
        }
        if (bytes.length > MAX_SCREENSHOT_BYTES) {
            throw new IllegalArgumentException("screenshot exceeds the 8 MB limit");
        }
        String fileKey = UUID.randomUUID().toString();
        genericFileService.save(BIZ_KEY, fileKey, fileKey + ".jpg", normalized, bytes);
        log.info("Screenshot stored: fileKey={}, contentType={}, size={}", fileKey, normalized, bytes.length);
        return new ScreenshotUploadResult(fileKey, normalized, bytes.length);
    }

    /** Reads a screenshot back by fileKey; returns null if it does not exist. */
    public StoredFile load(String fileKey) {
        return genericFileService.get(BIZ_KEY, fileKey);
    }

    /** Reads a screenshot and assembles an OpenAI-compatible data URL; returns null if missing or empty. */
    public String toDataUrl(String fileKey) {
        StoredFile stored = load(fileKey);
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            return null;
        }
        String contentType = stored.contentType() != null ? stored.contentType() : "application/octet-stream";
        if (!ALLOWED_TYPES.contains(contentType)) {
            return null;
        }
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(stored.content());
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String normalized = contentType.toLowerCase().split(";")[0].trim();
        return ALLOWED_TYPES.contains(normalized) ? normalized : null;
    }
}