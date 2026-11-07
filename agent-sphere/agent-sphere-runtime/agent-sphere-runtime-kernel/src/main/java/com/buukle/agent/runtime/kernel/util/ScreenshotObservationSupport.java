package com.buukle.agent.runtime.kernel.util;

import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared support for injecting browser screenshot observations (used by both main and sub agents):
 * - {@link #injectScreenshotObservation}: when a tool result contains data.screenshot → append a USER observation message
 *   [text + image_url dataURL]; when dataURL is null (file deleted) degrade to plain text.
 * - {@link #stripImageParts}: when no route supports images, collapse the last USER message to plain text (placeholder if empty).
 * - {@link #hasAttachmentImage}: decides whether the last USER message contains an image_url part.
 */
@Slf4j
public final class ScreenshotObservationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBSERVATION_TEXT_PREFIX = "[Browser screenshot observation]";

    /** Cap on screenshot injections per run/subRun (prevents the model from screenshotting endlessly and burning tokens). */
    private final ConcurrentHashMap<Long, AtomicInteger> screenshotCounts = new ConcurrentHashMap<>();
    private final int maxPerRun;
    private final ChatAttachmentResolver attachmentResolver;

    public ScreenshotObservationSupport(int maxPerRun, ChatAttachmentResolver attachmentResolver) {
        this.maxPerRun = maxPerRun;
        this.attachmentResolver = attachmentResolver;
    }

    /** Whether a tool result contains a browser screenshot reference (parse only; does not load the image). */
    public static boolean hasScreenshot(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        try {
            JsonNode shot = JSON.readTree(toolResult).path("data").path("screenshot");
            String fileKey = shot.path("fileKey").asText(null);
            return fileKey != null && !fileKey.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds a screenshot observation message from a tool result artifact; returns null when there is no screenshot.
     * The live path and the recovery path share the same format, so the model never sees two shapes of observation message.
     */
    public static ChatMessageDTO buildScreenshotObservation(ChatAttachmentResolver resolver, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(toolResult);
            JsonNode shot = root.path("data").path("screenshot");
            String fileKey = shot.path("fileKey").asText(null);
            if (fileKey == null || fileKey.isBlank()) {
                return null;
            }
            int width = shot.path("width").asInt(0);
            int height = shot.path("height").asInt(0);
            ChatMessagePartDTO textPart = new ChatMessagePartDTO()
                    .setType(ChatMessagePartDTO.TYPE_TEXT)
                    .setText(OBSERVATION_TEXT_PREFIX + " " + width + "x" + height);
            List<ChatMessagePartDTO> parts = new ArrayList<>();
            parts.add(textPart);
            String dataUrl = resolver == null ? null : resolver.toDataUrl(fileKey);
            if (dataUrl != null) {
                parts.add(new ChatMessagePartDTO()
                        .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                        .setImageUrl(new ChatMessagePartDTO.ImageUrl().setUrl(dataUrl)));
            }
            return new ChatMessageDTO()
                    .setRole(LlmRoles.USER)
                    .setContent(parts);
        } catch (Exception e) {
            log.warn("Screenshot observation build failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * When a browser screenshot tool result contains data.screenshot → append a USER observation message (text + image_url dataURL).
     * - fileKey missing/file deleted → skip that image part and keep only the text note (does not block the tool turn).
     * - Screenshot injections are capped per run (prevents the model from screenshotting endlessly and burning tokens).
     */
    public void injectScreenshotObservation(Long sessionId, Long runId,
                                            List<ChatMessageDTO> messages, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(toolResult);
            JsonNode shot = root.path("data").path("screenshot");
            String fileKey = shot.path("fileKey").asText(null);
            if (fileKey == null || fileKey.isBlank()) {
                return;
            }
            AtomicInteger counter = screenshotCounts.computeIfAbsent(runId, k -> new AtomicInteger());
            if (counter.get() >= maxPerRun) {
                log.warn("Screenshot cap reached for run {}", runId);
                return;
            }
            ChatMessageDTO observation = buildScreenshotObservation(attachmentResolver, toolResult);
            if (observation == null) {
                return;
            }
            counter.incrementAndGet();
            messages.add(observation);
        } catch (Exception e) {
            log.warn("Screenshot observation inject failed (session={}, run={}): {}", sessionId, runId, e.getMessage());
        }
    }

    /** Degradation when no route supports images: collapse the last USER message's parts into a plain text String (placeholder if empty). */
    public static void stripImageParts(List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ChatMessageDTO last = messages.get(messages.size() - 1);
        if (last == null || !LlmRoles.USER.equals(last.getRole())) {
            return;
        }
        Object content = last.getContent();
        if (!(content instanceof List<?> parts)) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof ChatMessagePartDTO p && ChatMessagePartDTO.TYPE_TEXT.equals(p.getType())
                    && p.getText() != null) {
                text.append(p.getText()).append(' ');
            }
        }
        String folded = text.toString().trim();
        last.setContent(folded.isEmpty() ? "[Image]" : folded);
    }

    /** Whether the current turn has image attachments: the last USER message content is parts and contains image_url. */
    public static boolean hasAttachmentImage(List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        ChatMessageDTO last = messages.get(messages.size() - 1);
        if (last == null || !LlmRoles.USER.equals(last.getRole())) {
            return false;
        }
        Object content = last.getContent();
        if (!(content instanceof List<?> parts)) {
            return false;
        }
        for (Object part : parts) {
            if (part instanceof ChatMessagePartDTO p && ChatMessagePartDTO.TYPE_IMAGE_URL.equals(p.getType())) {
                return true;
            }
        }
        return false;
    }

    /** For run terminal-state cleanup counting (prevents long-lived Map leaks). */
    public void clear(Long runId) {
        screenshotCounts.remove(runId);
    }
}