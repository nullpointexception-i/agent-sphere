package com.buukle.agent.infrastructure.service;

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
 * 浏览器截图落库：扩展捕获后以 base64 上报，按 (browser-screenshot, fileKey) 存
 * {@link GenericFileService}。截图文件与聊天附件并存于 agent_file_store，仅 bizKey 不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenshotFileService {

    public static final String BIZ_KEY = "browser-screenshot";
    private static final long MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final GenericFileService genericFileService;

    /** base64 截图落库；contentType 白名单外或超限抛 IllegalArgumentException。 */
    public ScreenshotUploadResult store(String base64, String contentType) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("截图内容不能为空");
        }
        String normalized = normalizeContentType(contentType);
        if (normalized == null) {
            throw new IllegalArgumentException("仅支持 jpeg/png/webp 截图");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("截图 base64 解码失败");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("截图内容不能为空");
        }
        if (bytes.length > MAX_SCREENSHOT_BYTES) {
            throw new IllegalArgumentException("截图超过 8MB 上限");
        }
        String fileKey = UUID.randomUUID().toString();
        genericFileService.save(BIZ_KEY, fileKey, fileKey + ".jpg", normalized, bytes);
        log.info("Screenshot stored: fileKey={}, contentType={}, size={}", fileKey, normalized, bytes.length);
        return new ScreenshotUploadResult(fileKey, normalized, bytes.length);
    }

    /** 按 fileKey 回读截图；不存在返回 null。 */
    public StoredFile load(String fileKey) {
        return genericFileService.get(BIZ_KEY, fileKey);
    }

    /** 读取截图并拼 OpenAI 兼容 data URL；不存在/无字节返回 null。 */
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