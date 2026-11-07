package com.buukle.agent.runtime.kernel.runner.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolCallDTO;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.runtime.kernel.util.ToolResultCompressor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection of the tool result budget used when building the LLM request.
 *
 * <p>Note: this only projects a copy of the request and <b>never modifies</b> SessionRunner's canonical messages.
 * canonical keeps the full tool results so run-local compaction / suspension snapshots see the original text, not placeholders.
 *
 * <ul>
 *   <li>Browser results: full text is kept by default; only a single result exceeding browserFullMaxChars (large pages) is replaced by a
 *       callId reference that can be read back with read_tool_result.</li>
 *   <li>Non-browser results: compressed under a per-item limit, the most recent N are always kept, and the rest are kept as far as the total budget allows.</li>
 * </ul>
 */
public final class LiveToolResultPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SKILL_LIBRARY_TOOL =
            InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.SKILL_LIBRARY.getId();

    private LiveToolResultPolicy() {
    }

    public static String browserToolName() {
        return InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.CHROME.getId();
    }

    public static boolean isBrowserTool(String toolName) {
        return browserToolName().equals(toolName);
    }

    public static boolean isSkillLibraryTool(String toolName) {
        return SKILL_LIBRARY_TOOL.equals(toolName);
    }

    public static List<ChatMessageDTO> project(List<ChatMessageDTO> messages, int maxChars, int budgetChars,
                                               int keepRecent, int browserFullMaxChars,
                                               Map<String, Boolean> compressByTool) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessageDTO> out = new ArrayList<>(messages.size());
        for (ChatMessageDTO m : messages) {
            out.add(copy(m));
        }

        Map<String, String> callNames = new HashMap<>();
        for (ChatMessageDTO m : out) {
            if (!LlmRoles.ASSISTANT.equals(m.getRole()) || m.getToolCalls() == null) {
                continue;
            }
            for (ToolCallDTO tc : m.getToolCalls()) {
                if (tc.getId() != null && tc.getFunction() != null) {
                    callNames.put(tc.getId(), tc.getFunction().getName());
                }
            }
        }

        int keptNonBrowser = 0;
        int usedNonBrowser = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            ChatMessageDTO m = out.get(i);
            if (!LlmRoles.TOOL.equals(m.getRole())) {
                continue;
            }
            if (!(m.getContent() instanceof String content)) {
                continue;
            }
            String name = callNames.get(m.getToolCallId());
            boolean compress = compressByTool != null && Boolean.TRUE.equals(compressByTool.get(name));
            if (isBrowserTool(name)) {
                if (compress && content.length() > browserFullMaxChars) {
                    m.setContent(browserReference(m.getToolCallId(), content));
                }
                continue;
            }

            String compressed = !compress
                    ? content
                    : (isSkillLibraryTool(name)
                        ? ToolResultCompressor.compressKeepArrays(content, maxChars)
                        : ToolResultCompressor.compress(content, maxChars));
            if (keptNonBrowser < keepRecent) {
                m.setContent(compressed);
                keptNonBrowser++;
                usedNonBrowser += compressed.length();
            } else if (usedNonBrowser + compressed.length() <= budgetChars) {
                m.setContent(compressed);
                usedNonBrowser += compressed.length();
            } else {
                m.setContent(toolReference(m.getToolCallId(), content.length()));
            }
        }
        return out;
    }

    /** Exceeds the full-text threshold: keep only a reference card; the model can read the full text back with read_tool_result. */
    public static String browserReference(String callId, String content) {
        int chars = content != null ? content.length() : 0;
        return "[Browser result omitted from context: callId=" + safeCallId(callId)
                + metadata(content) + "; chars=" + chars
                + ". Use read_tool_result({\"callId\":\"" + safeCallId(callId) + "\"}) to read it.]";
    }

    private static String toolReference(String callId, int chars) {
        return "[Tool result omitted from context: callId=" + safeCallId(callId)
                + "; chars=" + chars
                + ". Use read_tool_result({\"callId\":\"" + safeCallId(callId) + "\"}) to read it.]";
    }

    private static String safeCallId(String callId) {
        return callId == null || callId.isBlank() ? "unknown" : callId;
    }

    private static String metadata(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(content);
            JsonNode data = root.path("data");
            String url = data.path("_url").asText("");
            String title = data.path("_title").asText("");
            StringBuilder sb = new StringBuilder();
            if (!url.isBlank()) {
                sb.append("; url=").append(oneLine(url));
            }
            if (!title.isBlank()) {
                sb.append("; title=").append(oneLine(title));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String oneLine(String value) {
        String s = value.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static ChatMessageDTO copy(ChatMessageDTO m) {
        return new ChatMessageDTO()
                .setRole(m.getRole())
                .setContent(m.getContent())
                .setName(m.getName())
                .setToolCallId(m.getToolCallId())
                .setToolCalls(m.getToolCalls())
                .setReasoningContent(m.getReasoningContent());
    }
}
