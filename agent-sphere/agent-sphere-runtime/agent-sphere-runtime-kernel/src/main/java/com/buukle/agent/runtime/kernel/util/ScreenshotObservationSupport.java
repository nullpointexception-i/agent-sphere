package com.buukle.agent.runtime.kernel.util;

import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.runtime.kernel.constants.LlmApiConstant;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 浏览器截图观察注入公共支持（主/子 Agent 共用）：
 * - {@link #injectScreenshotObservation}：工具结果含 data.screenshot → 追加一条 USER 观察消息
 *   [text + image_url dataURL]；dataURL 为 null（文件被删）时降级纯文本。
 * - {@link #stripImageParts}：无支持图片的路由时把最后一条 USER 消息折叠为纯文本（空则占位）。
 * - {@link #hasAttachmentImage}：判定末条 USER 消息是否含 image_url 部分。
 */
@Slf4j
public final class ScreenshotObservationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBSERVATION_TEXT_PREFIX = "【浏览器截图观察】";

    /** run/subRun 级已注入截图次数上限（防模型无限截图烧 token）。 */
    private final ConcurrentHashMap<Long, AtomicInteger> screenshotCounts = new ConcurrentHashMap<>();
    private final int maxPerRun;
    private final ChatAttachmentResolver attachmentResolver;

    public ScreenshotObservationSupport(int maxPerRun, ChatAttachmentResolver attachmentResolver) {
        this.maxPerRun = maxPerRun;
        this.attachmentResolver = attachmentResolver;
    }

    /**
     * 浏览器截图工具结果含 data.screenshot → 追加一条 USER 观察消息（text + image_url dataURL）。
     * - fileKey 缺失/文件被删除 → 跳过该 image part，仅保留文本说明（不阻断工具回合）。
     * - 每 run 截图注入次数有上限（防模型无限截图烧 token）。
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
            counter.incrementAndGet();
            int width = shot.path("width").asInt(0);
            int height = shot.path("height").asInt(0);
            ChatMessagePartDTO textPart = new ChatMessagePartDTO()
                    .setType(ChatMessagePartDTO.TYPE_TEXT)
                    .setText(OBSERVATION_TEXT_PREFIX + " " + width + "x" + height);
            List<ChatMessagePartDTO> parts = new ArrayList<>();
            parts.add(textPart);
            String dataUrl = attachmentResolver.toDataUrl(fileKey);
            if (dataUrl != null) {
                parts.add(new ChatMessagePartDTO()
                        .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                        .setImageUrl(new ChatMessagePartDTO.ImageUrl().setUrl(dataUrl)));
            }
            messages.add(new ChatMessageDTO()
                    .setRole(LlmApiConstant.ROLE_USER)
                    .setContent(parts));
        } catch (Exception e) {
            log.warn("Screenshot observation inject failed (session={}, run={}): {}", sessionId, runId, e.getMessage());
        }
    }

    /** 无支持图片的路由时降级：把最后一条 USER 消息的 parts 折叠为纯文本 String（空则占位）。 */
    public static void stripImageParts(List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ChatMessageDTO last = messages.get(messages.size() - 1);
        if (last == null || !LlmApiConstant.ROLE_USER.equals(last.getRole())) {
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
        last.setContent(folded.isEmpty() ? "[图片]" : folded);
    }

    /** 当前轮是否含图片附件：末条 USER 消息 content 为 parts 且含 image_url。 */
    public static boolean hasAttachmentImage(List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        ChatMessageDTO last = messages.get(messages.size() - 1);
        if (last == null || !LlmApiConstant.ROLE_USER.equals(last.getRole())) {
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

    /** 供 run 终态清理计数（防长生命周期 Map 泄漏）。 */
    public void clear(Long runId) {
        screenshotCounts.remove(runId);
    }
}