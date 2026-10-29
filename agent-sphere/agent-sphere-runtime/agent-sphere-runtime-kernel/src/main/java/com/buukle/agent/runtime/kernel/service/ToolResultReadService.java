package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.ToolResultReadConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 历史工具结果分页读取：只读当前会话的 artifact，单次最多 8000 字符。 */
@Service
@RequiredArgsConstructor
public class ToolResultReadService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentToolCallRecordSpi toolCallRecordSpi;

    public String read(Long sessionId, String argumentsJson) {
        try {
            JsonNode args = JSON.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? RunnerConstants.EMPTY_JSON_ARGS : argumentsJson);
            String callId = args.path("callId").asText("");
            if (callId.isBlank()) {
                return error("callId_required", callId, null);
            }
            AgentToolCallRecordVO record = toolCallRecordSpi.getBySessionAndCallId(sessionId, callId);
            if (record == null) {
                return error("artifact_not_found", callId, null);
            }
            String artifact = record.getArtifact();
            if (artifact == null || artifact.isBlank()) {
                return error("artifact_empty", callId, record.getToolName());
            }
            int total = artifact.length();
            int offset = Math.max(0, args.path("offset").asInt(0));
            int maxChars = clamp(args.path("maxChars").asInt(ToolResultReadConstants.DEFAULT_MAX_CHARS));
            if (offset >= total) {
                offset = total;
            }
            int end = Math.min(total, offset + maxChars);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("callId", callId);
            out.put("toolName", record.getToolName());
            out.put("totalChars", total);
            out.put("offset", offset);
            out.put("nextOffset", end < total ? end : null);
            out.put("truncated", end < total);
            out.put("content", artifact.substring(offset, end));
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"read_failed\"}";
        }
    }

    private static int clamp(int value) {
        return Math.min(ToolResultReadConstants.MAX_MAX_CHARS,
                Math.max(ToolResultReadConstants.MIN_MAX_CHARS, value));
    }

    private static String error(String code, String callId, String toolName) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", code);
            out.put("callId", callId);
            if (toolName != null) {
                out.put("toolName", toolName);
            }
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"" + code + "\"}";
        }
    }
}
