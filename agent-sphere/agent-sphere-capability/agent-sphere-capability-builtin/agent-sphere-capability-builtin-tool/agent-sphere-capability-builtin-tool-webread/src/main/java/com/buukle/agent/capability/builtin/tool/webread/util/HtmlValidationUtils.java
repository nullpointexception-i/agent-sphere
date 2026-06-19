package com.buukle.agent.capability.builtin.tool.webread.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HTML 验证工具类 - 验证 HTML 内容的有效性
 */
@Slf4j
@Component
public class HtmlValidationUtils {

    private static final long MIN_CONTENT_LENGTH = 100;      // 最小内容长度（100 字节）
    private static final long MAX_CONTENT_LENGTH = 2097152L; // 最大内容长度（2MB）

    /**
     * 验证 HTML 内容是否有效
     *
     * @param html HTML 内容
     * @param maxLength 允许的最大长度
     * @return 如果有效返回 true
     */
    public boolean isValid(String html, long maxLength) {
        if (html == null) {
            log.warn("HTML content is null");
            return false;
        }

        long contentLength = html.length();

        // 检查最小长度
        if (contentLength < MIN_CONTENT_LENGTH) {
            log.warn("HTML content too short: {} bytes (min: {} bytes)", contentLength, MIN_CONTENT_LENGTH);
            return false;
        }

        // 检查最大长度
        if (contentLength > maxLength) {
            log.warn("HTML content too large: {} bytes (max: {} bytes)", contentLength, maxLength);
            return false;
        }

        // 检查是否为空白或仅包含标签
        if (html.trim().isEmpty() || isOnlyTags(html)) {
            log.warn("HTML content is empty or contains only tags");
            return false;
        }

        return true;
    }

    /**
     * 使用默认最大长度验证 HTML 内容
     */
    public boolean isValid(String html) {
        return isValid(html, MAX_CONTENT_LENGTH);
    }

    /**
     * 检查 HTML 内容是否仅包含标签（无实际内容）
     */
    private boolean isOnlyTags(String html) {
        // 移除所有 HTML 标签
        String plainText = html.replaceAll("<[^>]*>", "").trim();
        // 检查是否还有内容
        return plainText.isEmpty() || plainText.length() < 10;
    }

    /**
     * 获取 HTML 的可读内容长度（去除标签后）
     */
    public int getContentLength(String html) {
        if (html == null) {
            return 0;
        }
        return html.replaceAll("<[^>]*>", "").length();
    }

    /**
     * 检查 HTML 是否看起来像有效的 HTML 文档
     */
    public boolean looksLikeHtml(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        String lower = content.toLowerCase();
        // 检查是否包含常见的 HTML 标签
        return lower.contains("<html") || lower.contains("<body") || 
               lower.contains("<div") || lower.contains("<p") || 
               lower.contains("<!doctype");
    }

    /**
     * 清理 BOM 标记
     */
    public String removeBom(String content) {
        if (content != null && content.startsWith("\uFEFF")) {
            return content.substring(1);
        }
        return content;
    }
}
