package com.buukle.agent.instance.service.util;

import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具时间线的标准化元数据。
 *
 * <p>前端不应为每个工具解析完整 args/artifact；这里在服务端一次性提取展示所需字段，
 * 随 {@code content.meta} 下发。字段缺失时不写入，前端仍可回退解析原始 JSON。
 */
public final class ToolMetaResolver {

    private static final Pattern MCP_TOOL = Pattern.compile(
            "^" + InstanceCapabilityEnum.LLM_PREFIX_MCP + "(\\d+)_\\d+$");
    private static final int FIRST_LINE_MAX = 160;

    private ToolMetaResolver() {
    }

    public static Map<String, Object> build(AgentToolCallRecord record, CapabilityMcpSpi mcpSpi) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (record == null) {
            return meta;
        }
        JsonNode args = parse(record.getArgumentsJson());
        JsonNode artifact = parse(record.getArtifact());
        String tool = record.getToolName() == null ? "" : record.getToolName();

        put(meta, "status", record.getStatus());
        Long durationMs = durationMs(record);
        if (durationMs != null) {
            meta.put("durationMs", durationMs);
        }
        String error = firstText(record.getErrorMessage(),
                text(artifact, "error"), text(artifact, "errorMessage"));
        put(meta, "error", error);
        if (artifact != null && artifact.path("success").isBoolean()
                && !artifact.path("success").asBoolean()) {
            meta.put("failed", true);
        }

        switch (tool) {
            case "builtin_2", "builtin_4" -> webMeta(meta, args, artifact);
            case "builtin_11" -> textContainsMeta(meta, args, artifact);
            case "builtin_16" -> progressMeta(meta, args, artifact);
            case "builtin_8", "builtin_9" -> skillMeta(meta, args, artifact);
            case "builtin_14" -> readToolResultMeta(meta, artifact);
            case "builtin_5" -> browserMeta(meta, args, artifact);
            case "builtin_6", "builtin_15" -> docMeta(meta, args, artifact);
            default -> {
                Matcher mcp = MCP_TOOL.matcher(tool);
                if (mcp.matches()) {
                    mcpMeta(meta, mcp.group(1), mcpSpi);
                } else if (tool.startsWith("cli_")) {
                    cliMeta(meta, artifact);
                }
            }
        }
        return meta;
    }

    private static void webMeta(Map<String, Object> meta, JsonNode args, JsonNode artifact) {
        String body = firstText(text(artifact, "markdown"), text(artifact, "content"));
        put(meta, "url", text(args, "url"));
        put(meta, "host", host(text(args, "url")));
        put(meta, "title", text(artifact, "title"));
        if (artifact != null && artifact.path("statusCode").isNumber()) {
            meta.put("statusCode", artifact.path("statusCode").asInt());
        }
        if (body != null) {
            meta.put("chars", body.length());
        }
        if (artifact != null && artifact.path("truncated").isBoolean()) {
            meta.put("truncated", artifact.path("truncated").asBoolean());
        }
    }

    private static void textContainsMeta(Map<String, Object> meta, JsonNode args, JsonNode artifact) {
        put(meta, "needle", text(args, "needle"));
        put(meta, "mode", text(args, "mode"));
        String haystack = text(args, "haystack");
        if (haystack != null) {
            meta.put("haystackChars", haystack.length());
        }
        if (artifact != null && artifact.path("contains").isBoolean()) {
            meta.put("hit", artifact.path("contains").asBoolean());
        }
    }

    private static void progressMeta(Map<String, Object> meta, JsonNode args, JsonNode artifact) {
        put(meta, "summary", firstText(text(args, "summary"), text(artifact, "summary")));
        put(meta, "nextAction", text(args, "nextAction"));
        if (args != null && args.path("percent").isNumber()) {
            meta.put("percent", Math.max(0, Math.min(100, args.path("percent").asInt())));
        }
    }

    private static void skillMeta(Map<String, Object> meta, JsonNode args, JsonNode artifact) {
        put(meta, "action", firstText(text(args, "action"), text(artifact, "action")));
        put(meta, "name", firstText(text(args, "name"), text(artifact, "name")));
        if (artifact != null && artifact.path("total").isNumber()) {
            meta.put("total", artifact.path("total").asInt());
        }
        String preview = text(artifact, "preview");
        if (preview != null) {
            meta.put("previewChars", preview.length());
        }
        List<String> names = new ArrayList<>();
        JsonNode skills = artifact == null ? null : artifact.path("skills");
        if (skills != null && skills.isArray()) {
            for (JsonNode skill : skills) {
                String name = text(skill, "name");
                if (name != null) {
                    names.add(name);
                }
                if (names.size() >= 3) {
                    break;
                }
            }
        }
        if (!names.isEmpty()) {
            meta.put("names", names);
        }
    }

    private static void readToolResultMeta(Map<String, Object> meta, JsonNode artifact) {
        put(meta, "callId", text(artifact, "callId"));
        put(meta, "toolName", text(artifact, "toolName"));
        number(meta, "totalChars", artifact, "totalChars");
        number(meta, "offset", artifact, "offset");
        number(meta, "nextOffset", artifact, "nextOffset");
        if (artifact != null && artifact.path("truncated").isBoolean()) {
            meta.put("truncated", artifact.path("truncated").asBoolean());
        }
    }

    private static void browserMeta(Map<String, Object> meta, JsonNode args, JsonNode artifact) {
        put(meta, "action", text(args, "action"));
        String url = firstText(text(args, "url"), text(artifact == null ? null : artifact.path("data"), "url"));
        put(meta, "url", url);
        put(meta, "host", host(url));
        number(meta, "tabId", args, "tabId");
        put(meta, "errorCategory", text(artifact, "errorCategory"));
        put(meta, "method", text(artifact, "method"));
        put(meta, "resultType", text(artifact, "resultType"));
        put(meta, "warning", text(artifact, "warning"));
    }

    private static void docMeta(Map<String, Object> meta, JsonNode args, JsonNode artifact) {
        put(meta, "action", firstText(text(args, "action"), text(artifact, "action")));
        put(meta, "title", text(artifact, "title"));
        number(meta, "documentId", artifact, "documentId");
        number(meta, "version", artifact, "version");
        number(meta, "totalLines", artifact, "totalLines");
        String body = firstText(text(artifact, "content"), text(args, "content"));
        if (body != null) {
            meta.put("chars", body.length());
        }
    }

    private static void cliMeta(Map<String, Object> meta, JsonNode artifact) {
        if (artifact != null && artifact.path("exitCode").isNumber()) {
            meta.put("exitCode", artifact.path("exitCode").asInt());
        }
        String stdout = text(artifact, "stdout");
        String stderr = text(artifact, "stderr");
        if (stdout != null) {
            meta.put("stdoutChars", stdout.length());
        }
        if (stderr != null) {
            meta.put("stderrChars", stderr.length());
        }
        String output = firstText(stderr, stdout);
        if (output != null) {
            String firstLine = output.split("\n", 2)[0].trim();
            if (!firstLine.isEmpty()) {
                meta.put("firstLine", firstLine.length() > FIRST_LINE_MAX
                        ? firstLine.substring(0, FIRST_LINE_MAX) : firstLine);
            }
        }
    }

    private static void mcpMeta(Map<String, Object> meta, String id, CapabilityMcpSpi mcpSpi) {
        if (id == null || id.isBlank()) {
            return;
        }
        long mcpId;
        try {
            mcpId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return;
        }
        meta.put("serverId", mcpId);
        if (mcpSpi == null) {
            return;
        }
        try {
            var mcp = mcpSpi.getMcp(mcpId);
            if (mcp != null && mcp.getName() != null) {
                meta.put("server", mcp.getName());
            }
        } catch (Exception ignored) {
            // display meta is best-effort; never break timeline loading
        }
    }

    private static Long durationMs(AgentToolCallRecord record) {
        if (ToolCallRecordStatus.PENDING.name().equals(record.getStatus())) {
            return null;
        }
        if (record.getCreatedAt() == null || record.getUpdatedAt() == null) {
            return null;
        }
        long ms = Duration.between(record.getCreatedAt(), record.getUpdatedAt()).toMillis();
        return ms >= 0 ? ms : null;
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            return host != null && !host.isBlank() ? host : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isTextual() && !value.asText().isBlank()) {
            return value.asText();
        }
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void number(Map<String, Object> meta, String key, JsonNode node, String field) {
        if (node != null && node.path(field).isNumber()) {
            meta.put(key, node.path(field).asLong());
        }
    }

    private static void put(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.isBlank()) {
            meta.put(key, value);
        }
    }
}
