package com.buukle.agent.instance.service.util;

import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 工具/工具调用记录 artifact 中的浏览器截图引用解析：
 * artifact 形如 {"success":true,"data":{"screenshot":{"fileKey":"…","contentType":"image/jpeg",…}}}，
 * 提取 {fileKey, contentType} 供主/子 Agent 时间线回显图片。解析失败返回空列表（不阻断展示）。
 * 主 Agent（AgentTimelineServiceImpl TOOL 分支）与子 Agent（AgentSubAgentRunServiceImpl）共用。
 */
@Slf4j
public final class ScreenshotRefParser {

    private ScreenshotRefParser() {
    }

    /** 从工具结果 artifact JSON 解析截图引用；无截图/解析失败返回空列表。 */
    public static List<RunAttachment> extractScreenshotImages(String artifact) {
        if (artifact == null || artifact.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(artifact);
            JsonNode shot = root.path("data").path("screenshot");
            String fileKey = shot.path("fileKey").asText(null);
            String contentType = shot.path("contentType").asText(null);
            if (fileKey == null || fileKey.isBlank()) {
                return Collections.emptyList();
            }
            return List.of(new RunAttachment(fileKey,
                    contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream"));
        } catch (Exception e) {
            log.warn("Screenshot ref parse failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}