package com.buukle.agent.capability.builtin.tool.webread.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HTML validation utility - verifies that HTML content is usable
 */
@Slf4j
@Component
public class HtmlValidationUtils {

    private static final long MIN_CONTENT_LENGTH = 100;      // Minimum content length (100 bytes)
    private static final long MAX_CONTENT_LENGTH = 2097152L; // Maximum content length (2MB)

    /**
     * Validates whether the HTML content is usable
     *
     * @param html      HTML content
     * @param maxLength maximum allowed length
     * @return true when valid
     */
    public boolean isValid(String html, long maxLength) {
        if (html == null) {
            log.warn("HTML content is null");
            return false;
        }

        long contentLength = html.length();

        // Check minimum length
        if (contentLength < MIN_CONTENT_LENGTH) {
            log.warn("HTML content too short: {} bytes (min: {} bytes)", contentLength, MIN_CONTENT_LENGTH);
            return false;
        }

        // Check maximum length
        if (contentLength > maxLength) {
            log.warn("HTML content too large: {} bytes (max: {} bytes)", contentLength, maxLength);
            return false;
        }

        // Check whether it is blank or consists only of tags
        if (html.trim().isEmpty() || isOnlyTags(html)) {
            log.warn("HTML content is empty or contains only tags");
            return false;
        }

        return true;
    }

    /**
     * Validates HTML content using the default maximum length
     */
    public boolean isValid(String html) {
        return isValid(html, MAX_CONTENT_LENGTH);
    }

    /**
     * Checks whether the HTML content consists only of tags (no real content)
     */
    private boolean isOnlyTags(String html) {
        // Strip all HTML tags
        String plainText = html.replaceAll("<[^>]*>", "").trim();
        // Check whether any content remains
        return plainText.isEmpty() || plainText.length() < 10;
    }

    /**
     * Returns the readable content length of the HTML (after removing tags)
     */
    public int getContentLength(String html) {
        if (html == null) {
            return 0;
        }
        return html.replaceAll("<[^>]*>", "").length();
    }

    /**
     * Checks whether the HTML looks like a valid HTML document
     */
    public boolean looksLikeHtml(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        String lower = content.toLowerCase();
        // Check whether common HTML tags are present
        return lower.contains("<html") || lower.contains("<body") ||
                lower.contains("<div") || lower.contains("<p") ||
                lower.contains("<!doctype");
    }

    /**
     * Removes the BOM marker
     */
    public String removeBom(String content) {
        if (content != null && content.startsWith("\uFEFF")) {
            return content.substring(1);
        }
        return content;
    }
}
