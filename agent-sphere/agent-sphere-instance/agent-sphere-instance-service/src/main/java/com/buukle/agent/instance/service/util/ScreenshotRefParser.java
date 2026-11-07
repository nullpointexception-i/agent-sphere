package com.buukle.agent.instance.service.util;

import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * Parses browser screenshot references out of a tool/tool-call record artifact:
 * the artifact looks like {"success":true,"data":{"screenshot":{"fileKey":"…","contentType":"image/jpeg",…}}},
 * and {fileKey, contentType} is extracted so the main/sub-agent timeline can render the image.
 * A parse failure returns an empty list (display is never blocked).
 * Shared by the main Agent (AgentTimelineServiceImpl TOOL branch) and sub-agents (AgentSubAgentRunServiceImpl).
 */
@Slf4j
public final class ScreenshotRefParser {

    private ScreenshotRefParser() {
    }

    /** Parse screenshot references from a tool result artifact JSON; returns an empty list when there is no screenshot or parsing fails. */
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