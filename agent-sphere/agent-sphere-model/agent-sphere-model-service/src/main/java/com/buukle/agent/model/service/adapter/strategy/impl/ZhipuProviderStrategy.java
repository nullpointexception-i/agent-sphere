package com.buukle.agent.model.service.adapter.strategy.impl;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import java.util.ArrayList;
import java.util.List;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.service.stream.ToolStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class ZhipuProviderStrategy implements ProviderStrategy {

    @Override
    public ModelProviderCompany getCompany() {
        return ModelProviderCompany.GLM;
    }

    @Override
    public void adaptRequest(ChatCompletionRequestDTO request) {
        request.setToolStream(true);
    }

    @Override
    public void adaptResponse(JsonNode choice, ToolStream toolStream) {
        parseMessageToolCalls(choice, toolStream);
    }

    private void parseDeltaToolCalls(JsonNode choice, ToolStream toolStream) {
        if (!choice.has(LlmApiConstants.FIELD_DELTA)) return;
        JsonNode delta = choice.get(LlmApiConstants.FIELD_DELTA);
        if (delta.has(LlmApiConstants.FIELD_TOOL_CALLS)
                && delta.get(LlmApiConstants.FIELD_TOOL_CALLS).isArray()) {
            for (JsonNode tc : delta.get(LlmApiConstants.FIELD_TOOL_CALLS)) {
                int index = tc.has(LlmApiConstants.FIELD_INDEX)
                        ? tc.get(LlmApiConstants.FIELD_INDEX).asInt() : 0;
                String tcId = tc.has(LlmApiConstants.FIELD_ID)
                        ? tc.get(LlmApiConstants.FIELD_ID).asText() : null;
                String tcName = null;
                String tcArgs = null;
                if (tc.has(LlmApiConstants.FIELD_FUNCTION)) {
                    JsonNode fn = tc.get(LlmApiConstants.FIELD_FUNCTION);
                    if (fn.has(LlmApiConstants.FIELD_NAME) && !fn.get(LlmApiConstants.FIELD_NAME).isNull())
                        tcName = fn.get(LlmApiConstants.FIELD_NAME).asText();
                    if (fn.has(LlmApiConstants.FIELD_ARGUMENTS))
                        tcArgs = fn.get(LlmApiConstants.FIELD_ARGUMENTS).asText();
                }
                toolStream.onDelta(index, tcId, tcName, tcArgs);
            }
        }
    }

    @Override
    public ContentToolCallResult extractToolCallsFromContent(String content) {
        if (content == null || content.isBlank()) return ContentToolCallResult.EMPTY;
        content = content.replace(LlmApiConstants.CONTENT_WRAPPER_BEGIN, "").replace(LlmApiConstants.CONTENT_WRAPPER_END, "");

        int jsonStart = content.indexOf("{\"" + LlmApiConstants.FIELD_TOOL_CALLS + "\"");
        if (jsonStart < 0) jsonStart = content.indexOf("{\"" + LlmApiConstants.FIELD_ACTION + "\"");
        if (jsonStart < 0) jsonStart = content.indexOf('{');
        if (jsonStart < 0) return ContentToolCallResult.EMPTY;

        int jsonEnd = findMatchingBrace(content, jsonStart);
        if (jsonEnd < 0) return ContentToolCallResult.EMPTY;

        String jsonPart = content.substring(jsonStart, jsonEnd + 1);
        String prefix = jsonStart > 0 ? content.substring(0, jsonStart) : "";
        String suffix = jsonEnd + 1 < content.length() ? content.substring(jsonEnd + 1) : "";

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonPart);
            if (!root.isObject()) return ContentToolCallResult.EMPTY;

            if (root.has(LlmApiConstants.FIELD_TOOL_CALLS) && root.get(LlmApiConstants.FIELD_TOOL_CALLS).isArray()) {
                JsonNode calls = root.get(LlmApiConstants.FIELD_TOOL_CALLS);
                List<ContentToolCallResult.ContentToolCall> parsed = new ArrayList<>();
                for (JsonNode call : calls) {
                    if (!call.isObject()) continue;
                    String name = extractToolName(call);
                    if (name == null || name.isBlank()) continue;
                    JsonNode params = extractParams(call);
                    String args = params != null ? params.toString() : LlmApiConstants.EMPTY_JSON_ARGS;
                    parsed.add(new ContentToolCallResult.ContentToolCall(name, args));
                }
                if (!parsed.isEmpty()) {
                    ((ObjectNode) root).remove(LlmApiConstants.FIELD_TOOL_CALLS);
                    String cleanedContent = buildCleanedContent(prefix, root.toString(), suffix);
                    return new ContentToolCallResult(cleanedContent, parsed);
                }
            }

            if (root.has(LlmApiConstants.FIELD_ACTION)) {
                JsonNode action = root.get(LlmApiConstants.FIELD_ACTION);
                if (action.isObject()) {
                    String tool = action.has(LlmApiConstants.FIELD_TOOL) ? action.get(LlmApiConstants.FIELD_TOOL).asText() : null;
                    if (tool != null && !tool.isBlank()) {
                        JsonNode params = action.has(LlmApiConstants.FIELD_PARAMETERS) ? action.get(LlmApiConstants.FIELD_PARAMETERS) : null;
                        String args = params != null ? params.toString() : LlmApiConstants.EMPTY_JSON_ARGS;
                        ((ObjectNode) root).remove(LlmApiConstants.FIELD_ACTION);
                        String cleanedContent = buildCleanedContent(prefix, root.toString(), suffix);
                        return new ContentToolCallResult(cleanedContent, List.of(new ContentToolCallResult.ContentToolCall(tool, args)));
                    }
                }
            }

            return ContentToolCallResult.EMPTY;
        } catch (Exception e) {
            return ContentToolCallResult.EMPTY;
        }
    }

    private static String extractToolName(JsonNode call) {
        for (String key : List.of(LlmApiConstants.FIELD_NAME, LlmApiConstants.FIELD_TOOL, LlmApiConstants.FIELD_FUNC)) {
            if (call.has(key) && !call.get(key).isNull()) return call.get(key).asText();
        }
        return null;
    }

    private static JsonNode extractParams(JsonNode call) {
        for (String key : List.of(LlmApiConstants.FIELD_PARAMS, LlmApiConstants.FIELD_PARAMETERS,
                LlmApiConstants.FIELD_FUNC_ARGS, LlmApiConstants.FIELD_KWARGS)) {
            if (call.has(key)) return call.get(key);
        }
        return null;
    }

    private static int findMatchingBrace(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String buildCleanedContent(String prefix, String cleanedJson, String suffix) {
        StringBuilder sb = new StringBuilder();
        if (!prefix.isEmpty()) sb.append(prefix);
        if (!LlmApiConstants.EMPTY_JSON_ARGS.equals(cleanedJson)) sb.append(cleanedJson);
        if (!suffix.isEmpty()) sb.append(suffix);
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private void parseMessageToolCalls(JsonNode choice, ToolStream toolStream) {
        if (!choice.has(LlmApiConstants.FIELD_MESSAGE)) return;
        JsonNode msg = choice.get(LlmApiConstants.FIELD_MESSAGE);
        if (msg.has(LlmApiConstants.FIELD_TOOL_CALLS)
                && msg.get(LlmApiConstants.FIELD_TOOL_CALLS).isArray()) {
            for (JsonNode tc : msg.get(LlmApiConstants.FIELD_TOOL_CALLS)) {
                int index = tc.has(LlmApiConstants.FIELD_INDEX)
                        ? tc.get(LlmApiConstants.FIELD_INDEX).asInt() : 0;
                String tcId = tc.has(LlmApiConstants.FIELD_ID)
                        ? tc.get(LlmApiConstants.FIELD_ID).asText()
                        : "msg_" + System.nanoTime();
                String tcName = null;
                String tcArgs = null;
                if (tc.has(LlmApiConstants.FIELD_FUNCTION)) {
                    JsonNode fn = tc.get(LlmApiConstants.FIELD_FUNCTION);
                    if (fn.has(LlmApiConstants.FIELD_NAME) && !fn.get(LlmApiConstants.FIELD_NAME).isNull())
                        tcName = fn.get(LlmApiConstants.FIELD_NAME).asText();
                    if (fn.has(LlmApiConstants.FIELD_ARGUMENTS))
                        tcArgs = fn.get(LlmApiConstants.FIELD_ARGUMENTS).asText();
                }
                toolStream.onDelta(index, tcId, tcName, tcArgs);
            }
        }
    }
}
